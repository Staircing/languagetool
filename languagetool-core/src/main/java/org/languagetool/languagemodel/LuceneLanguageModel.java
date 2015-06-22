/* LanguageTool, a natural language style checker 
 * Copyright (C) 2014 Daniel Naber (http://www.danielnaber.de)
 * 
 * This library is free software; you can redistribute it and/or
 * modify it under the terms of the GNU Lesser General Public
 * License as published by the Free Software Foundation; either
 * version 2.1 of the License, or (at your option) any later version.
 *
 * This library is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the GNU
 * Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public
 * License along with this library; if not, write to the Free Software
 * Foundation, Inc., 51 Franklin St, Fifth Floor, Boston, MA  02110-1301
 * USA
 */
package org.languagetool.languagemodel;

import org.apache.commons.lang.StringUtils;
import org.apache.lucene.index.*;
import org.apache.lucene.search.*;
import org.apache.lucene.store.FSDirectory;

import java.io.File;
import java.io.IOException;
import java.util.*;

/**
 * Information about ngram occurrences, taken from a Lucene index.
 * @since 2.7
 */
public class LuceneLanguageModel implements LanguageModel {

  private static final Map<File,LuceneSearcher> dirToSearcherMap = new HashMap<>();  // static to save memory for language variants

  private final List<File> indexes = new ArrayList<>();
  private final Map<Integer,LuceneSearcher> luceneSearcherMap = new HashMap<>();
  private final File topIndexDir;

  /**
   * Throw RuntimeException is the given directory does not seem to be a valid ngram top directory
   * with sub directories {@code 1grams} etc.
   * @since 3.0
   */
  public static void validateDirectory(File topIndexDir) {
    if (!topIndexDir.exists() || !topIndexDir.isDirectory()) {
      throw new RuntimeException("Not found or is not a directory: " + topIndexDir);
    }
    List<String> dirs = new ArrayList<>();
    for (String name : topIndexDir.list()) {
      if (name.matches("[123]grams")) {
        dirs.add(name);
      }
    }
    if (dirs.size() == 0) {
      throw new RuntimeException("Directory must contain at least '1grams', '2grams', and '3grams': " + topIndexDir.getAbsolutePath());
    }
    if (dirs.size() < 3) {
      throw new RuntimeException("Expected at least '1grams', '2grams', and '3grams' sub directories but only got " + dirs + " in " + topIndexDir.getAbsolutePath());
    }
  }

  /**
   * @param topIndexDir a directory which contains at least another sub directory called {@code 3grams},
   *                    which is a Lucene index with ngram occurrences as created by
   *                    {@code org.languagetool.dev.FrequencyIndexCreator}.
   */
  public LuceneLanguageModel(File topIndexDir) throws IOException {
    validateDirectory(topIndexDir);
    this.topIndexDir = topIndexDir;
    addIndex(topIndexDir, 1);
    addIndex(topIndexDir, 2);
    addIndex(topIndexDir, 3);
    addIndex(topIndexDir, 4);
    if (luceneSearcherMap.size() == 0) {
      throw new RuntimeException("No directories '1grams' ... '3grams' found in " + topIndexDir);
    }
  }

  private void addIndex(File topIndexDir, int ngramSize) throws IOException {
    File indexDir = new File(topIndexDir, ngramSize + "grams");
    if (indexDir.exists() && indexDir.isDirectory()) {
      if (luceneSearcherMap.containsKey(ngramSize)) {
        throw new RuntimeException("Searcher for ngram size " + ngramSize + " already exists");
      }
      luceneSearcherMap.put(ngramSize, getCachedLuceneSearcher(indexDir));
      indexes.add(indexDir);
    }
  }

  @Override
  public long getCount(List<String> tokens) {
    Objects.requireNonNull(tokens);
    Term term = new Term("ngram", StringUtils.join(tokens, " "));
    return getCount(term, getLuceneSearcher(tokens.size()));
  }

  @Override
  public long getCount(String token1) {
    Objects.requireNonNull(token1);
    return getCount(Arrays.asList(token1));
  }

  @Override
  public long getCount(String token1, String token2) {
    Objects.requireNonNull(token1);
    Objects.requireNonNull(token2);
    return getCount(Arrays.asList(token1, token2));
  }

  @Override
  public long getCount(String token1, String token2, String token3) {
    Objects.requireNonNull(token1);
    Objects.requireNonNull(token2);
    Objects.requireNonNull(token3);
    return getCount(Arrays.asList(token1, token2, token3));
  }

  @Override
  public long getTotalTokenCount() {
    LuceneSearcher luceneSearcher = getLuceneSearcher(1);
    try {
      RegexpQuery query = new RegexpQuery(new Term("totalTokenCount", ".*"));
      TopDocs docs = luceneSearcher.searcher.search(query, 1000);  // Integer.MAX_VALUE might cause OOE on wrong index
      if (docs.totalHits == 0) {
        throw new RuntimeException("Expected 'totalTokenCount' meta documents not found in 1grams index");
      } else if (docs.totalHits > 1000) {
        throw new RuntimeException("Did not expect more than 1000 'totalTokenCount' meta documents");
      } else {
        long result = 0;
        for (ScoreDoc scoreDoc : docs.scoreDocs) {
          result += Long.parseLong(luceneSearcher.reader.document(scoreDoc.doc).get("totalTokenCount"));
        }
        return result;
      }
    } catch (IOException e) {
      throw new RuntimeException(e);
    }
  }

  protected LuceneSearcher getLuceneSearcher(int ngramSize) {
    LuceneSearcher luceneSearcher = luceneSearcherMap.get(ngramSize);
    if (luceneSearcher == null) {
      throw new RuntimeException("No " + ngramSize + "grams directory found in " + topIndexDir);
    }
    return luceneSearcher;
  }

  private LuceneSearcher getCachedLuceneSearcher(File indexDir) throws IOException {
    LuceneSearcher luceneSearcher = dirToSearcherMap.get(indexDir);
    if (luceneSearcher == null) {
      LuceneSearcher newSearcher = new LuceneSearcher(indexDir);
      dirToSearcherMap.put(indexDir, newSearcher);
      return newSearcher;
    } else {
      return luceneSearcher;
    }
  }

  private long getCount(Term term, LuceneSearcher luceneSearcher) {
    try {
      TopDocs docs = luceneSearcher.searcher.search(new TermQuery(term), 1);
      if (docs.totalHits > 0) {
        int docId = docs.scoreDocs[0].doc;
        return Long.parseLong(luceneSearcher.reader.document(docId).get("count"));
      } else {
        return 0;
      }
    } catch (IOException e) {
      throw new RuntimeException(e);
    }
  }

  @Override
  public void close() {
    for (LuceneSearcher searcher : luceneSearcherMap.values()) {
      try {
        searcher.reader.close();
        searcher.directory.close();
      } catch (IOException e) {
        throw new RuntimeException(e);
      }
    }
  }

  @Override
  public String toString() {
    return indexes.toString();
  }

  protected static class LuceneSearcher {
    final FSDirectory directory;
    final IndexReader reader;
    final IndexSearcher searcher;
    private LuceneSearcher(File indexDir) throws IOException {
      this.directory = FSDirectory.open(indexDir);
      this.reader = DirectoryReader.open(directory);
      this.searcher = new IndexSearcher(reader);
    }
    public IndexReader getReader() {
      return reader;
    }
  }
}
