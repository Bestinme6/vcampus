package com.vcampus.client.fx.forum;

import java.util.concurrent.*;
import java.util.function.Consumer;

final class ForumAsync implements AutoCloseable {
    private final ExecutorService workers;
    private final Consumer<Runnable> dispatch;
    private volatile long generation;
    private volatile boolean closed;
    ForumAsync(ExecutorService workers,Consumer<Runnable> dispatch){this.workers=workers;this.dispatch=dispatch;}
    void invalidate(){generation++;}
    <T> void run(Callable<T> work,Consumer<T> success,Consumer<String> failure){
        runDetailed(work,success,error->failure.accept(error.getMessage()==null?"操作失败，请稍后重试":error.getMessage()));
    }
    <T> void runDetailed(Callable<T> work,Consumer<T> success,Consumer<Throwable> failure){
        if(closed)return;long version=generation;
        CompletableFuture.supplyAsync(()->{try{return work.call();}catch(Exception e){throw new CompletionException(e);}},workers)
            .whenComplete((value,error)->{
                if(closed||version!=generation)return;
                dispatch.accept(()->{
                    if(closed||version!=generation)return;
                    if(error==null)success.accept(value);else{
                        Throwable cause=error;while(cause instanceof CompletionException&&cause.getCause()!=null)cause=cause.getCause();
                        failure.accept(cause);
                    }
                });
            });
    }
    @Override public void close(){closed=true;invalidate();}
}
