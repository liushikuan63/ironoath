package com.ironoath.web.bot;

import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Objects;
import java.util.TreeMap;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorCompletionService;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;
import java.util.function.IntFunction;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** Finite calibration batch; every callback remains responsible for its real player lock. */
@Component
public final class BotCalibrationBatch {
    private final int workers;
    private final IntFunction<ExecutorService> factory;

    @Autowired
    public BotCalibrationBatch(@Value("${ironoath.bot.calibration-workers:4}") int workers) {
        this(workers, Executors::newFixedThreadPool);
    }

    BotCalibrationBatch(int workers, IntFunction<ExecutorService> factory) {
        if(workers<1 || workers>8)throw new IllegalArgumentException("校准并发数必须在1..8之间");
        this.workers=workers;this.factory=java.util.Objects.requireNonNull(factory);
    }

    boolean canDispatchInParallel(int count) {
        return workers>1 && count>1 && !Thread.currentThread().isInterrupted()
                && !TransactionSynchronizationManager.isSynchronizationActive()
                && !TransactionSynchronizationManager.isActualTransactionActive()
                && TransactionSynchronizationManager.getResourceMap().isEmpty();
    }

    public <T> List<T> run(List<String> ids, Function<String,T> task, boolean parallelAllowed) {
        List<String> input=List.copyOf(ids);Objects.requireNonNull(task);
        if(!parallelAllowed || !canDispatchInParallel(input.size())) {
            List<T> results=new ArrayList<>();
            for(String id:input)results.add(task.apply(id));
            return List.copyOf(results);
        }
        int limit=Math.min(workers,input.size());
        ExecutorService executor=Objects.requireNonNull(factory.apply(limit));
        var completed=new ExecutorCompletionService<Indexed<T>>(executor);
        var inFlight=new IdentityHashMap<Future<Indexed<T>>,Integer>();
        var failures=new TreeMap<Integer,Throwable>();
        List<T> results=new ArrayList<>(Collections.nCopies(input.size(),null));
        int next=0;boolean stopped=false,interrupted=false;
        InterruptedException interruption=null;
        try {
            while(next<input.size() || !inFlight.isEmpty()) {
                while(!stopped && next<input.size() && inFlight.size()<limit) {
                    if(Thread.interrupted()) {
                        interrupted=true;stopped=true;
                        if(interruption==null)interruption=new InterruptedException("派发校准时被中断");
                        break;
                    }
                    int index=next;
                    try {
                        Future<Indexed<T>> future=completed.submit(()->new Indexed<>(index,task.apply(input.get(index))));
                        inFlight.put(future,index);next++;
                    } catch(RuntimeException | Error failure) { failures.put(index,failure);stopped=true; }
                }
                if(inFlight.isEmpty())break;
                Future<Indexed<T>> future;
                try { future=completed.take(); }
                catch(InterruptedException failure) {
                    interrupted=true;stopped=true;if(interruption==null)interruption=failure;continue;
                }
                int index=inFlight.get(future);boolean received=false;
                while(!received) {
                    try { Indexed<T> value=future.get();results.set(value.index(),value.value());received=true; }
                    catch(InterruptedException failure) { interrupted=true;stopped=true;if(interruption==null)interruption=failure; }
                    catch(ExecutionException failure) { failures.put(index,failure.getCause());stopped=true;received=true; }
                    catch(CancellationException failure) { failures.put(index,failure);stopped=true;received=true; }
                }
                inFlight.remove(future);
            }
        } finally {
            // Accepted writes must finish; interruption/rejection is never permission to cancel them.
            executor.shutdown();boolean terminated=false;
            while(!terminated) {
                try { terminated=executor.awaitTermination(1,TimeUnit.SECONDS); }
                catch(InterruptedException failure) { interrupted=true;if(interruption==null)interruption=failure; }
            }
            if(interrupted)Thread.currentThread().interrupt();
        }
        if(!failures.isEmpty()) {
            Throwable primary=failures.firstEntry().getValue();
            for(Throwable failure:failures.values())if(failure!=primary)primary.addSuppressed(failure);
            if(interruption!=null && interruption!=primary)primary.addSuppressed(interruption);
            throw propagate(primary);
        }
        if(interrupted)throw new IllegalStateException("等待每日校准被中断",interruption);
        return List.copyOf(results);
    }

    private record Indexed<T>(int index,T value) { }

    private static RuntimeException propagate(Throwable cause) {
        if(cause instanceof Error error)throw error;
        return cause instanceof RuntimeException failure?failure:new IllegalStateException("每日校准任务失败",cause);
    }
}
