/*
 * Copyright Camunda Services GmbH and/or licensed to Camunda Services GmbH
 * under one or more contributor license agreements. See the NOTICE file
 * distributed with this work for additional information regarding copyright
 * ownership. Camunda licenses this file to you under the Apache License,
 * Version 2.0; you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.cibseven.bpm.engine.query;

import java.util.Iterator;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.Spliterator;
import java.util.Spliterators;
import java.util.stream.Stream;
import java.util.stream.StreamSupport;

import org.cibseven.bpm.engine.BadUserRequestException;
import org.cibseven.bpm.engine.ProcessEngineException;

/**
 * Describes basic methods for querying.
 *
 * @author Frederik Heremans
 */
public interface Query<T extends Query< ? , ? >, U extends Object> {

  /**
   * Order the results ascending on the given property as defined in this
   * class (needs to come after a call to one of the orderByXxxx methods).
   */
  T asc();

  /**
   * Order the results descending on the given property as defined in this
   * class (needs to come after a call to one of the orderByXxxx methods).
   */
  T desc();

  /** Executes the query and returns the number of results */
  long count();

  /**
   * Executes the query and returns the resulting entity or null if no
   * entity matches the query criteria.
   * @throws ProcessEngineException when the query results in more than one
   * entities.
   */
  U singleResult();

  /**
   * Executes the query and get a list of entities as the result.
   *
   * @return a list of results
   * @throws BadUserRequestException
   *   When a maximum results limit is specified. A maximum results limit can be specified with
   *   the process engine configuration property <code>queryMaxResultsLimit</code> (default
   *   {@link Integer#MAX_VALUE}).
   *   Please use {@link #listPage(int, int)} instead.
   */
  List<U> list();

  /**
   * Executes the query. No limitation checks are performed (e. g. query limit).
   *
   * @return a list of results
   */
  List<U> unlimitedList();

  /**
   * Executes the query and get a list of entities as the result.
   *
   * @param firstResult the index of the first result
   * @param maxResults the maximum number of results
   * @return a list of results
   * @throws BadUserRequestException
   *   When {@param maxResults} exceeds the maximum results limit. A maximum results limit can
   *   be specified with the process engine configuration property <code>queryMaxResultsLimit</code>
   *   (default {@link Integer#MAX_VALUE}).
   */
  List<U> listPage(int firstResult, int maxResults);

  /**
   * Executes the query and returns the results as a lazily evaluated {@link Stream}.
   *
   * <p>The stream fetches the results in fixed-size pages from the database on demand (via
   * {@link #listPage(int, int)}) as it is consumed, so the whole result set is never held in
   * memory at once. This is a convenience over paginating manually with
   * {@link #listPage(int, int)}.
   *
   * <p><b>Ordering matters:</b> because the results are fetched page by page, the query must
   * define a <em>stable total order</em> - i.e. an ordering on a unique property (such as the
   * entity id) - otherwise rows may be duplicated or skipped between pages, just like with
   * manual pagination. Non-unique orderings (or no ordering at all) do not guarantee a stable
   * order between page requests.
   *
   * <p>Each page is fetched in its own transaction, so the stream does not observe a single
   * consistent snapshot: concurrent modifications happening while the stream is being consumed
   * may or may not be reflected. The stream is sequential and should be consumed once.
   *
   * @return a lazily evaluated stream of results
   * @throws BadUserRequestException
   *   see {@link #listPage(int, int)}
   */
  default Stream<U> stream() {
    final int pageSize = 100;

    Iterator<U> iterator = new Iterator<U>() {

      protected int nextFirstResult = 0;
      protected List<U> page = null;
      protected int indexInPage = 0;
      protected boolean lastPageReached = false;

      @Override
      public boolean hasNext() {
        if (page != null && indexInPage < page.size()) {
          return true;
        }
        if (lastPageReached) {
          return false;
        }
        page = listPage(nextFirstResult, pageSize);
        nextFirstResult += pageSize;
        indexInPage = 0;
        // a page smaller than the requested size means there are no further pages
        if (page.size() < pageSize) {
          lastPageReached = true;
        }
        return indexInPage < page.size();
      }

      @Override
      public U next() {
        if (!hasNext()) {
          throw new NoSuchElementException();
        }
        return page.get(indexInPage++);
      }
    };

    Spliterator<U> spliterator =
        Spliterators.spliteratorUnknownSize(iterator, Spliterator.ORDERED | Spliterator.NONNULL);
    return StreamSupport.stream(spliterator, false);
  }

}
