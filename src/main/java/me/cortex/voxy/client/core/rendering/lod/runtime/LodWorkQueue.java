package me.cortex.voxy.client.core.rendering.lod.runtime;

import java.util.*;
import java.util.concurrent.CancellationException;
import java.util.function.*;

/** Bounded background work. Reservations include running jobs and unconsumed output.
 * Caller supplies conservative whole-job memory estimates; actual JVM allocations
 * cannot be measured/enforced here. poll validates the authoritative world revision.
 */
public final class LodWorkQueue<K,V> implements AutoCloseable {
    public record Completed<K,V>(K key,long revision,V value,Throwable failure) {}
    private final class Job {
        final K key;final long revision,sequence,bytes;final int priority;
        final Supplier<V> action;volatile boolean cancelled;Thread runner;
        Completed<K,V> result;
        Job(K key,long revision,int priority,long bytes,Supplier<V> action){this.key=key;this.revision=revision;this.priority=priority;this.bytes=bytes;this.action=action;this.sequence=nextSequence++;}
    }
    private final PriorityQueue<Job> pending=new PriorityQueue<>(Comparator.<Job>comparingInt(j->j.priority).thenComparingLong(j->j.sequence));
    private final Map<K,Job> current=new HashMap<>();
    private final ArrayDeque<Job> completed=new ArrayDeque<>();
    private final Thread[] workers;
    private final int maxJobs;private final long maxBytes;
    private int jobs;private long reserved,nextSequence;private boolean closed;
    public LodWorkQueue(int threads,int maxJobs,long maxBytes) {
        if(threads<1||maxJobs<threads||maxBytes<1)throw new IllegalArgumentException("Invalid work budget");
        this.maxJobs=maxJobs;this.maxBytes=maxBytes;workers=new Thread[threads];
        for(int i=0;i<threads;i++){workers[i]=new Thread(this::work,"fragment-lod-worker-"+i);workers[i].setDaemon(true);workers[i].start();}
    }
    /** Lower priority values run first. Main/render thread never waits for capacity. */
    public synchronized boolean submit(K key,long revision,int priority,long bytes,Supplier<V> action) {
        Objects.requireNonNull(key);Objects.requireNonNull(action);
        if(bytes<1||bytes>maxBytes)throw new IllegalArgumentException("Invalid memory reservation");
        if(closed)return false;
        Job old=current.get(key);
        if(old!=null&&revision<=old.revision)return false;
        // Pending/output replacements can release their reservation immediately.
        long reclaim=old!=null&&old.runner==null?old.bytes:0;
        int reclaimJobs=reclaim>0?1:0;
        if(jobs-reclaimJobs>=maxJobs||reserved-reclaim>maxBytes-bytes)return false;
        if(old!=null)cancel(old);
        Job job=new Job(key,revision,priority,bytes,action);pending.add(job);current.put(key,job);reserved+=bytes;jobs++;notifyAll();return true;
    }
    public synchronized void invalidate(K key){Job job=current.get(key);if(job!=null)cancel(job);}
    private void cancel(Job job) {
        job.cancelled=true;
        if(job.runner!=null)job.runner.interrupt();
        else {pending.remove(job);completed.remove(job);release(job);}
    }
    private void release(Job job){reserved-=job.bytes;jobs--;current.remove(job.key,job);}
    public Completed<K,V> poll(BiPredicate<K,Long> isCurrent) {
        Objects.requireNonNull(isCurrent);
        while(true) {
            Job job;
            synchronized(this) {
                if(completed.isEmpty())return null;
                job=completed.removeFirst();release(job);
            }
            // External revision lookup must not run under the worker queue lock.
            // The caller still validates/commits ownership on its publication thread.
            if(!job.cancelled&&isCurrent.test(job.key,job.revision))return job.result;
        }
    }
    public synchronized long reservedBytes(){return reserved;}
    public synchronized int jobCount(){return jobs;}
    private void work() {
        while(true) {
            Job job;
            synchronized(this) {
                while(pending.isEmpty()&&!closed)try{wait();}catch(InterruptedException ignored){}
                if(closed)return;
                job=pending.remove();job.runner=Thread.currentThread();
            }
            V value=null;Throwable failure=null;
            try {if(!job.cancelled)value=job.action.get();}
            catch(Throwable error){failure=error;}
            finally {
                synchronized(this) {
                    job.runner=null;
                    if(closed||job.cancelled||failure instanceof CancellationException)release(job);
                    else {job.result=new Completed<>(job.key,job.revision,value,failure);completed.addLast(job);}
                    Thread.interrupted();notifyAll();
                }
            }
        }
    }
    @Override public synchronized void close() {
        if(closed)return;closed=true;
        for(Job job:new ArrayList<>(current.values()))cancel(job);
        for(Thread worker:workers)worker.interrupt();notifyAll();
    }
}
