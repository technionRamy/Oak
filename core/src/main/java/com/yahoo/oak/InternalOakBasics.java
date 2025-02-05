/*
 * Copyright 2020, Verizon Media.
 * Licensed under the terms of the Apache 2.0 license.
 * Please see LICENSE file in the project root for terms.
 */

package com.yahoo.oak;

import java.io.IOException;
import java.util.concurrent.atomic.AtomicInteger;

abstract class InternalOakBasics<K, V> {
    /*-------------- Members --------------*/
    protected static final int MAX_RETRIES = 1024;

    protected final MemoryManager valuesMemoryManager;
    protected final MemoryManager keysMemoryManager;
    protected final AtomicInteger size;

    /*-------------- Constructors --------------*/
    InternalOakBasics(MemoryManager vMM, MemoryManager kMM) {
        this.size = new AtomicInteger(0);
        this.valuesMemoryManager = vMM;
        this.keysMemoryManager = kMM;
    }

    /*-------------- Closable --------------*/
    /**
     * cleans only off heap memory
     */
    void close() {
        try {
            // closing the same memory manager (or memory allocator) twice,
            // has the same effect as closing once
            valuesMemoryManager.close();
            keysMemoryManager.close();
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    /*-------------- size --------------*/
    /**
     * @return current off heap memory usage in bytes
     */
    long memorySize() {
        if (valuesMemoryManager != keysMemoryManager) {
            // Two memory managers are not the same instance, but they
            // may still have the same allocator and allocator defines how many bytes are allocated
            if (valuesMemoryManager.getBlockMemoryAllocator()
                != keysMemoryManager.getBlockMemoryAllocator()) {
                return valuesMemoryManager.allocated() + keysMemoryManager.allocated();
            }
        }
        return valuesMemoryManager.allocated();
    }

    int entries() {
        return size.get();
    }

    /*-------------- Context --------------*/
    /**
     * Should only be called from API methods at the beginning of the method and be reused in internal calls.
     *
     * @return a context instance.
     */
    ThreadContext getThreadContext() {
        return new ThreadContext(keysMemoryManager, valuesMemoryManager);
    }

    /*-------------- REBALANCE --------------*/
    /**
    * Tunneling for a specific chunk rebalance to be implemented in concrete internal map or hash
    * */
    protected abstract void rebalanceBasic(BasicChunk<K, V> c);

    protected void checkRebalance(BasicChunk<K, V> c) {
        if (c.shouldRebalance()) {
            rebalanceBasic(c);
        }
    }

    protected void helpRebalanceIfInProgress(BasicChunk<K, V> c) {
        if (c.state() == BasicChunk.State.FROZEN) {
            rebalanceBasic(c);
        }
    }

    protected boolean inTheMiddleOfRebalance(BasicChunk<K, V> c) {
        BasicChunk.State state = c.state();
        if (state == BasicChunk.State.INFANT) {
            // the infant is already connected so rebalancer won't add this put
            rebalanceBasic(c.creator());
            return true;
        }
        if (state == BasicChunk.State.FROZEN || state == BasicChunk.State.RELEASED) {
            rebalanceBasic(c);
            return true;
        }
        return false;
    }

    /*-------------- Common actions --------------*/
    protected boolean finalizeDeletion(BasicChunk<K, V> c, ThreadContext ctx) {
        if (c.finalizeDeletion(ctx)) {
            rebalanceBasic(c);
            return true;
        }
        return false;
    }

    protected boolean isAfterRebalanceOrValueUpdate(BasicChunk<K, V> c, ThreadContext ctx) {
        // If orderedChunk is frozen or infant, can't proceed with put, need to help rebalancer first,
        // rebalance is done as part of inTheMiddleOfRebalance.
        // Also if value is off-heap deleted, we need to finalizeDeletion on-heap, which can
        // cause rebalance as well. If rebalance happened finalizeDeletion returns true.
        // After rebalance we need to restart.
        if (inTheMiddleOfRebalance(c) || finalizeDeletion(c, ctx)) {
            return true;
        }

        // Value can be valid again, if key was found and partially deleted value needed help.
        // But in the meanwhile value was reset to be another, valid value.
        // In Hash case value will be always invalid in the context, but the changes will be caught
        // during next entry allocation
        if (ctx.isValueValid()) {
            return true;
        }

        return false;
    }

    /**
     * See {@code refreshValuePosition(ctx)} for more details.
     *
     * @param key   the key to refresh
     * @param value the output value to update
     * @return true if the refresh was successful.
     */
    boolean refreshValuePosition(KeyBuffer key, ValueBuffer value) {
        ThreadContext ctx = getThreadContext();
        ctx.key.copyFrom(key);
        boolean isSuccessful = refreshValuePosition(ctx);

        if (!isSuccessful) {
            return false;
        }

        value.copyFrom(ctx.value);
        return true;
    }

    /**
     * Used when value of a key was possibly moved and we try to search for the given key
     * through the OakMap again.
     *
     * @param ctx The context key should be initialized with the key to refresh, and the context value
     *            will be updated with the refreshed value.
     * @reutrn true if the refresh was successful.
     */
    abstract boolean refreshValuePosition(ThreadContext ctx);

    /*-------------- Different Oak Buffer creations --------------*/

    protected UnscopedBuffer getKeyUnscopedBuffer(ThreadContext ctx) {
        return new UnscopedBuffer<>(new KeyBuffer(ctx.key));
    }

    protected UnscopedValueBufferSynced getValueUnscopedBuffer(ThreadContext ctx) {
        return new UnscopedValueBufferSynced(ctx.key, ctx.value, this);
    }

}



