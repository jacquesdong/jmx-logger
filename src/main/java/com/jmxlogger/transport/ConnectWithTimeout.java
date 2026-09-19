package com.jmxlogger.transport;

import java.io.IOException;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * 把"可能永久挂着"的建连动作放到守护线程里跑，并按 {@code --timeout} 限时。
 *
 * <p>两种场景都需要它：
 * <ul>
 *   <li>RMI 握手（JNDI 查注册表 + 连数据端口）没有超时参数，目标不作答就会一直等；</li>
 *   <li>本地 attach 遇到正处于 stop-the-world 的目标进程时同样会一直等。</li>
 * </ul>
 * 超时后放弃这一次连接（线程是 daemon，不阻碍 JVM 退出）。
 *
 * <p>包内可见：这是传输层的实现细节，不是对外 API。
 */
final class ConnectWithTimeout {

    private ConnectWithTimeout() {
    }

    /**
     * 执行建连动作。
     *
     * @param timeoutMillis 超时（毫秒），{@code <= 0} 表示不限制（直接在当前线程执行）
     * @param timeoutMessage 超时时的报错文案；各传输方式的原因不同，由调用方给出可操作提示。
     *                       {@code <= 0} 时用不到，可传 null。
     */
    static <T> T call(Callable<T> task, long timeoutMillis, String timeoutMessage) throws IOException {
        if (timeoutMillis <= 0) {
            return callDirectly(task);
        }

        ExecutorService executor = Executors.newSingleThreadExecutor(new ThreadFactory() {
            @Override
            public Thread newThread(Runnable r) {
                Thread t = new Thread(r, "jmx-logger-connect");
                t.setDaemon(true);
                return t;
            }
        });
        try {
            Future<T> future = executor.submit(task);
            try {
                return future.get(timeoutMillis, TimeUnit.MILLISECONDS);
            } catch (TimeoutException e) {
                future.cancel(true);
                throw new IOException(timeoutMessage, e);
            } catch (ExecutionException e) {
                Throwable cause = e.getCause();
                if (cause instanceof IOException) {
                    throw (IOException) cause;
                }
                if (cause instanceof RuntimeException) {
                    throw (RuntimeException) cause;
                }
                if (cause instanceof Error) {
                    throw (Error) cause;
                }
                throw new IOException(String.valueOf(cause), cause);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IOException("连接被中断", e);
            }
        } finally {
            executor.shutdownNow();
        }
    }

    private static <T> T callDirectly(Callable<T> task) throws IOException {
        try {
            return task.call();
        } catch (IOException e) {
            throw e;
        } catch (RuntimeException e) {
            throw e;
        } catch (Error e) {
            throw e;
        } catch (Exception e) {
            throw new IOException(String.valueOf(e), e);
        }
    }
}
