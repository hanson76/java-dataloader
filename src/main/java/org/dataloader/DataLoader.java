/*
 * Copyright (c) 2016 The original author or authors
 *
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License v1.0
 * and Apache License v2.0 which accompanies this distribution.
 *
 *      The Eclipse Public License is available at
 *      http://www.eclipse.org/legal/epl-v10.html
 *
 *      The Apache License v2.0 is available at
 *      http://www.opensource.org/licenses/apache2.0.php
 *
 * You may elect to redistribute this code under either of these licenses.
 */

package org.dataloader;

import org.dataloader.impl.CompletableFutureKit;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.stream.Collectors;

import static java.util.Collections.emptyList;
import static org.dataloader.impl.Assertions.assertState;
import static org.dataloader.impl.Assertions.nonNull;

/**
 * Data loader is a utility class that allows batch loading of data that is identified by a set of unique keys. For
 * each key that is loaded a separate {@link CompletableFuture} is returned, that completes as the batch function completes.
 * <p>
 * With batching enabled the execution will start after calling {@link DataLoader#dispatch()}, causing the queue of
 * loaded keys to be sent to the batch function, clears the queue, and returns a promise to the values.
 * <p>
 * As batch functions are executed the resulting futures are cached using a cache implementation of choice, so they
 * will only execute once. Individual cache keys can be cleared, so they will be re-fetched when referred to again.
 * It is also possible to clear the cache entirely, and prime it with values before they are used.
 * <p>
 * Both caching and batching can be disabled. Configuration of the data loader is done by providing a
 * {@link DataLoaderOptions} instance on creation.
 *
 * @param <K> type parameter indicating the type of the data load keys
 * @param <V> type parameter indicating the type of the data that is returned
 *
 * @author <a href="https://github.com/aschrijver/">Arnold Schrijver</a>
 * @author <a href="https://github.com/bbakerman/">Brad Baker</a>
 */
public class DataLoader<K, V> {

    private final BatchLoader<K, V> batchLoadFunction;
    private final DataLoaderOptions loaderOptions;
    private final CacheMap<Object, CompletableFuture<V>> futureCache;
    private final Map<K, CompletableFuture<V>> loaderQueue;

    /**
     * Creates a new data loader with the provided batch load function, and default options.
     *
     * @param batchLoadFunction the batch load function to use
     */
    public DataLoader(BatchLoader<K, V> batchLoadFunction) {
        this(batchLoadFunction, null);
    }

    /**
     * Creates a new data loader with the provided batch load function and options.
     *
     * @param batchLoadFunction the batch load function to use
     * @param options           the batch load options
     */
    public DataLoader(BatchLoader<K, V> batchLoadFunction, DataLoaderOptions options) {
        this.batchLoadFunction = nonNull(batchLoadFunction);
        this.loaderOptions = options == null ? new DataLoaderOptions() : options;
        this.futureCache = determineCacheMap(loaderOptions);
        // order of keys matter in data loader
        this.loaderQueue = new LinkedHashMap<>();
    }

    @SuppressWarnings("unchecked")
    private CacheMap<Object, CompletableFuture<V>> determineCacheMap(DataLoaderOptions loaderOptions) {
        return loaderOptions.cacheMap().isPresent() ? (CacheMap<Object, CompletableFuture<V>>) loaderOptions.cacheMap().get() : CacheMap.simpleMap();
    }

    /**
     * Requests to load the data with the specified key asynchronously, and returns a future of the resulting value.
     * <p>
     * If batching is enabled (the default), you'll have to call {@link DataLoader#dispatch()} at a later stage to
     * start batch execution. If you forget this call the future will never be completed (unless already completed,
     * and returned from cache).
     *
     * @param key the key to load
     *
     * @return the future of the value
     */
    public CompletableFuture<V> load(K key) {
        Object cacheKey = getCacheKey(nonNull(key));
        if (loaderOptions.cachingEnabled() && futureCache.containsKey(cacheKey)) {
            return futureCache.get(cacheKey);
        }

        CompletableFuture<V> future = new CompletableFuture<>();
        if (loaderOptions.batchingEnabled()) {
            synchronized (loaderQueue) {
                loaderQueue.put(key, future);
            }
        } else {
            // immediate execution of batch function (but not promise itself)
            CompletableFuture<List<V>> batchedLoad = batchLoadFunction
                    .load(Collections.singletonList(key))
                    .toCompletableFuture();
            future = batchedLoad
                    .thenApply(list -> list.get(0));
        }
        if (loaderOptions.cachingEnabled()) {
            futureCache.set(cacheKey, future);
        }
        return future;
    }

    /**
     * Requests to load the list of data provided by the specified keys asynchronously, and returns a composite future
     * of the resulting values.
     * <p>
     * If batching is enabled (the default), you'll have to call {@link DataLoader#dispatch()} at a later stage to
     * start batch execution. If you forget this call the future will never be completed (unless already completed,
     * and returned from cache).
     *
     * @param keys the list of keys to load
     *
     * @return the composite future of the list of values
     */
    public CompletableFuture<List<V>> loadMany(List<K> keys) {
        synchronized (loaderQueue) {

            List<CompletableFuture<V>> collect = keys.stream()
                    .map(this::load)
                    .collect(Collectors.toList());

            return CompletableFutureKit.allOf(collect);
        }
    }

    /**
     * Dispatches the queued load requests to the batch execution function and returns a promise of the result.
     * <p>
     * If batching is disabled, or there are no queued requests, then a succeeded promise is returned.
     *
     * @return the promise of the queued load requests
     */
    public CompletableFuture<List<V>> dispatch() {
        //
        // we copy the pre-loaded set of futures ready for dispatch
        final List<K> keys = new ArrayList<>();
        final List<CompletableFuture<V>> queuedFutures = new ArrayList<>();
        synchronized (loaderQueue) {
            loaderQueue.forEach((key, future) -> {
                keys.add(key);
                queuedFutures.add(future);
            });
            loaderQueue.clear();
        }
        if (!loaderOptions.batchingEnabled() || keys.size() == 0) {
            return CompletableFuture.completedFuture(emptyList());
        }
        //
        // order of keys -> values matter in data loader hence the use of linked hash map
        //
        // See https://github.com/facebook/dataloader/blob/master/README.md for more details
        //

        //
        // when the promised list of values completes, we transfer the values into
        // the previously cached future objects that the client already has been given
        // via calls to load("foo") and loadMany(["foo","bar"])
        //
        return batchLoadFunction.load(keys)
                .toCompletableFuture()
                .thenApply(values -> {
                    assertState(keys.size() == values.size(), "The size of the promised values MUST be the same size as the key list");

                    for (int idx = 0; idx < queuedFutures.size(); idx++) {
                        V value = values.get(idx);
                        CompletableFuture<V> future = queuedFutures.get(idx);
                        future.complete(value);
                    }
                    return values;
                }).exceptionally(ex -> {
                    for (int idx = 0; idx < queuedFutures.size(); idx++) {
                        K key = keys.get(idx);
                        CompletableFuture<V> future = queuedFutures.get(idx);
                        // clear any cached view of this key
                        futureCache.delete(key);
                        future.completeExceptionally(ex);

                    }
                    return emptyList();
                });
    }

    /**
     * Normally {@link #dispatch()} is an asynchronous operation but this version will 'join' on the
     * results if dispatch and wait for them to complete.  If the {@link CompletableFuture} callbacks make more
     * calls to this data loader then the {@link #dispatchDepth()} will be &gt; 0 and this method will loop
     * around and wait for any other extra batch loads to occur.
     *
     * @return the list of all results when the {@link #dispatchDepth()} reached 0
     */
    public List<V> dispatchAndJoin() {
        List<V> results = new ArrayList<>();

        List<V> joinedResults = dispatch().join();
        results.addAll(joinedResults);
        while (this.dispatchDepth() > 0) {
            joinedResults = dispatch().join();
            results.addAll(joinedResults);
        }
        return results;
    }


    /**
     * @return the depth of the batched key loads that need to be dispatched
     */
    public int dispatchDepth() {
        synchronized (loaderQueue) {
            return loaderQueue.size();
        }
    }


    /**
     * Clears the future with the specified key from the cache, if caching is enabled, so it will be re-fetched
     * on the next load request.
     *
     * @param key the key to remove
     *
     * @return the data loader for fluent coding
     */
    public DataLoader<K, V> clear(K key) {
        Object cacheKey = getCacheKey(key);
        futureCache.delete(cacheKey);
        return this;
    }

    /**
     * Clears the entire cache map of the loader.
     *
     * @return the data loader for fluent coding
     */
    public DataLoader<K, V> clearAll() {
        futureCache.clear();
        return this;
    }

    /**
     * Primes the cache with the given key and value.
     *
     * @param key   the key
     * @param value the value
     *
     * @return the data loader for fluent coding
     */
    public DataLoader<K, V> prime(K key, V value) {
        Object cacheKey = getCacheKey(key);
        if (!futureCache.containsKey(cacheKey)) {
            futureCache.set(cacheKey, CompletableFuture.completedFuture(value));
        }
        return this;
    }

    /**
     * Primes the cache with the given key and error.
     *
     * @param key   the key
     * @param error the exception to prime instead of a value
     *
     * @return the data loader for fluent coding
     */
    public DataLoader<K, V> prime(K key, Exception error) {
        Object cacheKey = getCacheKey(key);
        if (!futureCache.containsKey(cacheKey)) {
            futureCache.set(cacheKey, CompletableFutureKit.failedFuture(error));
        }
        return this;
    }

    /**
     * Gets the object that is used in the internal cache map as key, by applying the cache key function to
     * the provided key.
     * <p>
     * If no cache key function is present in {@link DataLoaderOptions}, then the returned value equals the input key.
     *
     * @param key the input key
     *
     * @return the cache key after the input is transformed with the cache key function
     */
    @SuppressWarnings("unchecked")
    public Object getCacheKey(K key) {
        return loaderOptions.cacheKeyFunction().isPresent() ?
                loaderOptions.cacheKeyFunction().get().getKey(key) : key;
    }
}
