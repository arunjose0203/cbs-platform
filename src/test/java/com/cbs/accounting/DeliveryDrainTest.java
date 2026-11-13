package com.cbs.accounting;

import static org.assertj.core.api.Assertions.*;
import java.util.*;
import org.junit.jupiter.api.Test;

/** P-06: a delivery pass empties the queue in batches of 20 and never spins on items that keep failing. */
class DeliveryDrainTest {
    /** A queue like the delivery tables: 20 per batch, ordered by last attempt (failed items move to the back). */
    static final class Queue {
        final Deque<Integer> items=new ArrayDeque<>();final Set<Integer> failing;final List<Integer> delivered=new ArrayList<>();int batches;
        Queue(int size,Set<Integer> failing){for(int i=0;i<size;i++)items.add(i);this.failing=failing;}
        List<Integer> pending(){batches++;return items.stream().limit(20).toList();}
        void deliver(Integer id){items.remove(id);if(failing.contains(id)){items.addLast(id);throw new IllegalStateException("provider down");}delivered.add(id);}
    }
    @Test void drainsTheWholeQueueInOnePass(){
        var q=new Queue(95,Set.of());
        assertThat(LedgerDeliveryWorker.drain(10_000,q::pending,q::deliver,"Test")).isEqualTo(95);
        assertThat(q.items).isEmpty();assertThat(q.delivered).hasSize(95);assertThat(q.batches).isEqualTo(5);
    }
    @Test void attemptsEachFailingItemOnceAndStops(){
        var q=new Queue(45,Set.of(3,7,40));
        assertThat(LedgerDeliveryWorker.drain(10_000,q::pending,q::deliver,"Test")).isEqualTo(45);
        assertThat(q.delivered).hasSize(42);assertThat(q.items).containsExactlyInAnyOrder(3,7,40);assertThat(q.batches).isLessThanOrEqualTo(4);
    }
    @Test void anUnavailableQueueEndsThePassQuietly(){
        assertThat(LedgerDeliveryWorker.<Integer>drain(10_000,()->{throw new IllegalStateException("db down");},id->{},"Test")).isZero();
    }
    @Test void aFullQueueOfFailuresDoesNotSpinUntilTheBudget(){
        var all=new HashSet<Integer>();for(int i=0;i<20;i++)all.add(i);var q=new Queue(20,all);
        long start=System.nanoTime();LedgerDeliveryWorker.drain(10_000,q::pending,q::deliver,"Test");
        assertThat((System.nanoTime()-start)/1_000_000).isLessThan(2_000);assertThat(q.batches).isEqualTo(2);
    }
}
