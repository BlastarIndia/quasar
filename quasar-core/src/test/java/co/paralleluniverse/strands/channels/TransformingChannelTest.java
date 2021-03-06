/*
 * Quasar: lightweight threads and actors for the JVM.
 * Copyright (c) 2013-2014, Parallel Universe Software Co. All rights reserved.
 * 
 * This program and the accompanying materials are dual-licensed under
 * either the terms of the Eclipse Public License v1.0 as published by
 * the Eclipse Foundation
 *  
 *   or (per the licensee's choosing)
 *  
 * under the terms of the GNU Lesser General Public License version 3.0
 * as published by the Free Software Foundation.
 */
package co.paralleluniverse.strands.channels;

import static co.paralleluniverse.common.test.Matchers.*;
import co.paralleluniverse.common.util.Action2;
import co.paralleluniverse.common.util.Debug;
import co.paralleluniverse.common.util.Function2;
import co.paralleluniverse.fibers.Fiber;
import co.paralleluniverse.fibers.FiberForkJoinScheduler;
import co.paralleluniverse.fibers.FiberScheduler;
import co.paralleluniverse.fibers.SuspendExecution;
import co.paralleluniverse.strands.Strand;
import co.paralleluniverse.strands.SuspendableAction2;
import co.paralleluniverse.strands.SuspendableCallable;
import co.paralleluniverse.strands.SuspendableRunnable;
import co.paralleluniverse.strands.channels.Channels.OverflowPolicy;
import co.paralleluniverse.strands.queues.QueueCapacityExceededException;
import com.google.common.base.Function;
import com.google.common.base.Predicate;
import java.util.Arrays;
import java.util.Collection;
import java.util.concurrent.TimeUnit;
import static org.hamcrest.CoreMatchers.*;
import org.junit.After;
import static org.junit.Assert.*;
import static org.junit.Assume.*;
import org.junit.Before;
import org.junit.Ignore;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TestName;
import org.junit.rules.TestRule;
import org.junit.rules.TestWatcher;
import org.junit.runner.Description;
import org.junit.runner.RunWith;
import org.junit.runners.Parameterized;

/**
 *
 * @author pron
 */
@RunWith(Parameterized.class)
public class TransformingChannelTest {
    @Rule
    public TestName name = new TestName();
    @Rule
    public TestRule watchman = new TestWatcher() {
        @Override
        protected void starting(Description desc) {
            if (Debug.isDebug()) {
                System.out.println("STARTING TEST " + desc.getMethodName());
                Debug.record(0, "STARTING TEST " + desc.getMethodName());
            }
        }

        @Override
        public void failed(Throwable e, Description desc) {
            System.out.println("FAILED TEST " + desc.getMethodName() + ": " + e.getMessage());
            e.printStackTrace(System.err);
            if (Debug.isDebug() && !(e instanceof OutOfMemoryError)) {
                Debug.record(0, "EXCEPTION IN THREAD " + Thread.currentThread().getName() + ": " + e + " - " + Arrays.toString(e.getStackTrace()));
                Debug.dumpRecorder("~/quasar.dump");
            }
        }

        @Override
        protected void succeeded(Description desc) {
            Debug.record(0, "DONE TEST " + desc.getMethodName());
        }
    };
    final int mailboxSize;
    final OverflowPolicy policy;
    final boolean singleConsumer;
    final boolean singleProducer;
    final FiberScheduler scheduler;

//    public ChannelTest() {
//        fjPool = new ForkJoinPool(4, ForkJoinPool.defaultForkJoinWorkerThreadFactory, null, true);
//        this.mailboxSize = 0;
//        this.policy = OverflowPolicy.BLOCK;
//        this.singleConsumer = false;
//        this.singleProducer = false;
//
//        Debug.dumpAfter(20000, "channels.log");
//    }
    public TransformingChannelTest(int mailboxSize, OverflowPolicy policy, boolean singleConsumer, boolean singleProducer) {
        scheduler = new FiberForkJoinScheduler("test", 4, null, false);
        this.mailboxSize = mailboxSize;
        this.policy = policy;
        this.singleConsumer = singleConsumer;
        this.singleProducer = singleProducer;
    }

    @Parameterized.Parameters
    public static Collection<Object[]> data() {
        return Arrays.asList(new Object[][]{
            {5, OverflowPolicy.THROW, true, false},
            {5, OverflowPolicy.THROW, false, false},
            {5, OverflowPolicy.BLOCK, true, false},
            {5, OverflowPolicy.BLOCK, false, false},
            {1, OverflowPolicy.BLOCK, false, false},
            {-1, OverflowPolicy.THROW, true, false},
            {5, OverflowPolicy.DISPLACE, true, false},
            {0, OverflowPolicy.BLOCK, false, false},});
    }

    private <Message> Channel<Message> newChannel() {
        return Channels.newChannel(mailboxSize, policy, singleProducer, singleConsumer);
    }

    @Before
    public void setUp() {
    }

    @After
    public void tearDown() {
    }

    @Test
    public void transformingReceiveChannelIsEqualToChannel() throws Exception {
        final Channel<Integer> ch = newChannel();
        ReceivePort<Integer> ch1 = Channels.filter((ReceivePort<Integer>) ch, new Predicate<Integer>() {
            @Override
            public boolean apply(Integer input) {
                return input % 2 == 0;
            }
        });
        ReceivePort<Integer> ch2 = Channels.map((ReceivePort<Integer>) ch, new Function<Integer, Integer>() {
            @Override
            public Integer apply(Integer input) {
                return input + 10;
            }
        });

        ReceivePort<Integer> ch3 = Channels.flatmap((ReceivePort<Integer>) ch, new Function<Integer, ReceivePort<Integer>>() {
            @Override
            public ReceivePort<Integer> apply(Integer input) {
                return Channels.toReceivePort(Arrays.asList(new Integer[]{input * 10, input * 100, input * 1000}));
            }
        });

        assertTrue(ch1.equals(ch));
        assertTrue(ch.equals(ch1));
        assertTrue(ch2.equals(ch));
        assertTrue(ch.equals(ch2));
        assertTrue(ch3.equals(ch));
        assertTrue(ch.equals(ch3));

        assertTrue(ch1.equals(ch1));
        assertTrue(ch1.equals(ch2));
        assertTrue(ch1.equals(ch3));
        assertTrue(ch2.equals(ch1));
        assertTrue(ch2.equals(ch2));
        assertTrue(ch2.equals(ch3));
        assertTrue(ch3.equals(ch1));
        assertTrue(ch3.equals(ch2));
        assertTrue(ch3.equals(ch3));
    }

    @Test
    public void transformingSendChannelIsEqualToChannel() throws Exception {
        final Channel<Integer> ch = newChannel();
        SendPort<Integer> ch1 = Channels.filterSend((SendPort<Integer>) ch, new Predicate<Integer>() {
            @Override
            public boolean apply(Integer input) {
                return input % 2 == 0;
            }
        });
        SendPort<Integer> ch2 = Channels.mapSend((SendPort<Integer>) ch, new Function<Integer, Integer>() {
            @Override
            public Integer apply(Integer input) {
                return input + 10;
            }
        });

        assertTrue(ch1.equals(ch));
        assertTrue(ch.equals(ch1));
        assertTrue(ch2.equals(ch));
        assertTrue(ch.equals(ch2));
        assertTrue(ch1.equals(ch2));
    }

    @Test
    public void testFilterFiberToFiber() throws Exception {
        final Channel<Integer> ch = newChannel();

        Fiber fib1 = new Fiber("fiber", scheduler, new SuspendableRunnable() {
            @Override
            public void run() throws SuspendExecution, InterruptedException {
                ReceivePort<Integer> ch1 = Channels.filter((ReceivePort<Integer>) ch, new Predicate<Integer>() {
                    @Override
                    public boolean apply(Integer input) {
                        return input % 2 == 0;
                    }
                });

                Integer m1 = ch1.receive();
                Integer m2 = ch1.receive();
                Integer m3 = ch1.receive();

                assertThat(m1, equalTo(2));
                assertThat(m2, equalTo(4));
                assertThat(m3, is(nullValue()));
            }
        }).start();

        Fiber fib2 = new Fiber("fiber", scheduler, new SuspendableRunnable() {
            @Override
            public void run() throws SuspendExecution, InterruptedException {
                Strand.sleep(50);
                ch.send(1);
                ch.send(2);
                Strand.sleep(50);
                ch.send(3);
                ch.send(4);
                ch.send(5);
                ch.close();
            }
        }).start();

        fib1.join();
        fib2.join();
    }

    @Test
    public void testFilterThreadToFiber() throws Exception {
        final Channel<Integer> ch = newChannel();

        Fiber fib = new Fiber("fiber", scheduler, new SuspendableRunnable() {
            @Override
            public void run() throws SuspendExecution, InterruptedException {
                ReceivePort<Integer> ch1 = Channels.filter((ReceivePort<Integer>) ch, new Predicate<Integer>() {
                    @Override
                    public boolean apply(Integer input) {
                        return input % 2 == 0;
                    }
                });

                Integer m1 = ch1.receive();
                Integer m2 = ch1.receive();
                Integer m3 = ch1.receive();

                assertThat(m1, equalTo(2));
                assertThat(m2, equalTo(4));
                assertThat(m3, is(nullValue()));
            }
        }).start();

        Strand.sleep(50);
        ch.send(1);
        ch.send(2);
        Strand.sleep(50);
        ch.send(3);
        ch.send(4);
        ch.send(5);
        ch.close();

        fib.join();
    }

    @Test
    public void testFilterFiberToThread() throws Exception {
        final Channel<Integer> ch = newChannel();

        Fiber fib = new Fiber("fiber", scheduler, new SuspendableRunnable() {
            @Override
            public void run() throws SuspendExecution, InterruptedException {
                Fiber.sleep(100);

                Strand.sleep(50);
                ch.send(1);
                ch.send(2);
                Strand.sleep(50);
                ch.send(3);
                ch.send(4);
                ch.send(5);
                ch.close();
            }
        }).start();

        ReceivePort<Integer> ch1 = Channels.filter((ReceivePort<Integer>) ch, new Predicate<Integer>() {
            @Override
            public boolean apply(Integer input) {
                return input % 2 == 0;
            }
        });

        Integer m1 = ch1.receive();
        Integer m2 = ch1.receive();
        Integer m3 = ch1.receive();

        assertThat(m1, equalTo(2));
        assertThat(m2, equalTo(4));
        assertThat(m3, is(nullValue()));

        fib.join();
    }

    @Test
    public void testFilterWithTimeouts() throws Exception {
        final Channel<Integer> ch = newChannel();

        Fiber fib = new Fiber("fiber", scheduler, new SuspendableRunnable() {
            @Override
            public void run() throws SuspendExecution, InterruptedException {
                ReceivePort<Integer> ch1 = Channels.filter((ReceivePort<Integer>) ch, new Predicate<Integer>() {
                    @Override
                    public boolean apply(Integer input) {
                        return input % 2 == 0;
                    }
                });

                Integer m1 = ch1.receive();
                Integer m0 = ch1.receive(30, TimeUnit.MILLISECONDS);
                Integer m2 = ch1.receive(50, TimeUnit.MILLISECONDS);
                Integer m3 = ch1.receive();

                assertThat(m1, equalTo(2));
                assertThat(m0, is(nullValue()));
                assertThat(m2, equalTo(4));
                assertThat(m3, is(nullValue()));
            }
        }).start();

        Strand.sleep(50);
        ch.send(1);
        ch.send(2);
        Strand.sleep(50);
        ch.send(3);
        ch.send(4);
        ch.send(5);
        ch.close();

        fib.join();
    }

    @Test
    public void testSendFilterFiberToFiber() throws Exception {
        final Channel<Integer> ch = newChannel();

        Fiber fib1 = new Fiber("fiber", scheduler, new SuspendableRunnable() {
            @Override
            public void run() throws SuspendExecution, InterruptedException {
                Integer m1 = ch.receive();
                Integer m2 = ch.receive();
                Integer m3 = ch.receive();

                assertThat(m1, equalTo(2));
                assertThat(m2, equalTo(4));
                assertThat(m3, is(nullValue()));
            }
        }).start();

        Fiber fib2 = new Fiber("fiber", scheduler, new SuspendableRunnable() {
            @Override
            public void run() throws SuspendExecution, InterruptedException {
                SendPort<Integer> ch1 = Channels.filterSend((SendPort<Integer>) ch, new Predicate<Integer>() {
                    @Override
                    public boolean apply(Integer input) {
                        return input % 2 == 0;
                    }
                });

                Strand.sleep(50);
                ch1.send(1);
                ch1.send(2);
                Strand.sleep(50);
                ch1.send(3);
                ch1.send(4);
                ch1.send(5);
                ch1.close();
            }
        }).start();

        fib1.join();
        fib2.join();
    }

    @Test
    public void testSendFilterThreadToFiber() throws Exception {
        final Channel<Integer> ch = newChannel();

        Fiber fib = new Fiber("fiber", scheduler, new SuspendableRunnable() {
            @Override
            public void run() throws SuspendExecution, InterruptedException {
                Integer m1 = ch.receive();
                Integer m2 = ch.receive();
                Integer m3 = ch.receive();

                assertThat(m1, equalTo(2));
                assertThat(m2, equalTo(4));
                assertThat(m3, is(nullValue()));
            }
        }).start();

        SendPort<Integer> ch1 = Channels.filterSend((SendPort<Integer>) ch, new Predicate<Integer>() {
            @Override
            public boolean apply(Integer input) {
                return input % 2 == 0;
            }
        });

        Strand.sleep(50);
        ch1.send(1);
        ch1.send(2);
        Strand.sleep(50);
        ch1.send(3);
        ch1.send(4);
        ch1.send(5);
        ch1.close();

        fib.join();
    }

    @Test
    public void testSendFilterFiberToThread() throws Exception {
        final Channel<Integer> ch = newChannel();

        Fiber fib = new Fiber("fiber", scheduler, new SuspendableRunnable() {
            @Override
            public void run() throws SuspendExecution, InterruptedException {
                Fiber.sleep(100);

                SendPort<Integer> ch1 = Channels.filterSend((SendPort<Integer>) ch, new Predicate<Integer>() {
                    @Override
                    public boolean apply(Integer input) {
                        return input % 2 == 0;
                    }
                });

                Strand.sleep(50);
                ch1.send(1);
                ch1.send(2);
                Strand.sleep(50);
                ch1.send(3);
                ch1.send(4);
                ch1.send(5);
                ch1.close();
            }
        }).start();

        Integer m1 = ch.receive();
        Integer m2 = ch.receive();
        Integer m3 = ch.receive();

        assertThat(m1, equalTo(2));
        assertThat(m2, equalTo(4));
        assertThat(m3, is(nullValue()));

        fib.join();
    }

    @Test
    public void testSendFilterWithTimeouts() throws Exception {
        final Channel<Integer> ch = newChannel();

        Fiber fib = new Fiber("fiber", scheduler, new SuspendableRunnable() {
            @Override
            public void run() throws SuspendExecution, InterruptedException {
                Integer m1 = ch.receive();
                Integer m0 = ch.receive(30, TimeUnit.MILLISECONDS);
                Integer m2 = ch.receive(80, TimeUnit.MILLISECONDS);
                Integer m3 = ch.receive();

                assertThat(m1, equalTo(2));
                assertThat(m0, is(nullValue()));
                assertThat(m2, equalTo(4));
                assertThat(m3, is(nullValue()));
            }
        }).start();

        SendPort<Integer> ch1 = Channels.filterSend((SendPort<Integer>) ch, new Predicate<Integer>() {
            @Override
            public boolean apply(Integer input) {
                return input % 2 == 0;
            }
        });

        Strand.sleep(50);
        ch1.send(1);
        ch1.send(2);
        Strand.sleep(50);
        ch1.send(3);
        ch1.send(4);
        ch1.send(5);
        ch1.close();

        fib.join();
    }

    @Test
    public void testMapThreadToFiber() throws Exception {
        final Channel<Integer> ch = newChannel();

        Fiber fib = new Fiber("fiber", scheduler, new SuspendableRunnable() {
            @Override
            public void run() throws SuspendExecution, InterruptedException {
                ReceivePort<Integer> ch1 = Channels.map((ReceivePort<Integer>) ch, new Function<Integer, Integer>() {
                    @Override
                    public Integer apply(Integer input) {
                        return input + 10;
                    }
                });

                Integer m1 = ch1.receive();
                Integer m2 = ch1.receive();
                Integer m3 = ch1.receive();
                Integer m4 = ch1.receive();
                Integer m5 = ch1.receive();
                Integer m6 = ch1.receive();

                assertThat(m1, equalTo(11));
                assertThat(m2, equalTo(12));
                assertThat(m3, equalTo(13));
                assertThat(m4, equalTo(14));
                assertThat(m5, equalTo(15));
                assertThat(m6, is(nullValue()));
            }
        }).start();

        Strand.sleep(50);
        ch.send(1);
        ch.send(2);
        Strand.sleep(50);
        ch.send(3);
        ch.send(4);
        ch.send(5);
        ch.close();

        fib.join();
    }

    @Test
    public void testSendMapThreadToFiber() throws Exception {
        final Channel<Integer> ch = newChannel();

        Fiber fib = new Fiber("fiber", scheduler, new SuspendableRunnable() {
            @Override
            public void run() throws SuspendExecution, InterruptedException {
                Integer m1 = ch.receive();
                Integer m2 = ch.receive();
                Integer m3 = ch.receive();
                Integer m4 = ch.receive();
                Integer m5 = ch.receive();
                Integer m6 = ch.receive();

                assertThat(m1, equalTo(11));
                assertThat(m2, equalTo(12));
                assertThat(m3, equalTo(13));
                assertThat(m4, equalTo(14));
                assertThat(m5, equalTo(15));
                assertThat(m6, is(nullValue()));
            }
        }).start();

        SendPort<Integer> ch1 = Channels.mapSend((SendPort<Integer>) ch, new Function<Integer, Integer>() {
            @Override
            public Integer apply(Integer input) {
                return input + 10;
            }
        });

        Strand.sleep(50);
        ch1.send(1);
        ch1.send(2);
        Strand.sleep(50);
        ch1.send(3);
        ch1.send(4);
        ch1.send(5);
        ch1.close();

        fib.join();
    }

    @Test
    public void testZipThreadToFiber() throws Exception {
        final Channel<String> ch1 = newChannel();
        final Channel<Integer> ch2 = newChannel();

        Fiber fib = new Fiber("fiber", scheduler, new SuspendableRunnable() {
            @Override
            public void run() throws SuspendExecution, InterruptedException {
                ReceivePort<String> ch = Channels.zip(ch1, ch2, new Function2<String, Integer, String>() {
                    @Override
                    public String apply(String x1, Integer x2) {
                        return x1 + x2;
                    }
                });

                String m1 = ch.receive();
                String m2 = ch.receive();
                String m3 = ch.receive();
                String m4 = ch.receive();

                assertThat(m1, equalTo("a1"));
                assertThat(m2, equalTo("b2"));
                assertThat(m3, equalTo("c3"));
                assertThat(m4, is(nullValue()));
            }
        }).start();

        Strand.sleep(50);
        ch1.send("a");
        ch2.send(1);
        ch1.send("b");
        ch2.send(2);
        ch1.send("c");
        ch2.send(3);
        ch1.send("foo");
        ch2.close();
        fib.join();
    }

    @Test
    public void testZipWithTimeoutsThreadToFiber() throws Exception {
        final Channel<String> ch1 = newChannel();
        final Channel<Integer> ch2 = newChannel();

        Fiber fib = new Fiber("fiber", scheduler, new SuspendableRunnable() {
            @Override
            public void run() throws SuspendExecution, InterruptedException {
                ReceivePort<String> ch = Channels.zip(ch1, ch2, new Function2<String, Integer, String>() {
                    @Override
                    public String apply(String x1, Integer x2) {
                        return x1 + x2;
                    }
                });

                String m1 = ch.receive();
                String m0 = ch.receive(30, TimeUnit.MILLISECONDS);
                String m2 = ch.receive(40, TimeUnit.MILLISECONDS);
                String m3 = ch.receive();
                String m4 = ch.receive();

                assertThat(m1, equalTo("a1"));
                assertThat(m0, is(nullValue()));
                assertThat(m2, equalTo("b2"));
                assertThat(m3, equalTo("c3"));
                assertThat(m4, is(nullValue()));
            }
        }).start();

        Strand.sleep(50);
        ch1.send("a");
        ch2.send(1);
        ch1.send("b");
        Strand.sleep(50);
        ch2.send(2);
        ch1.send("c");
        ch2.send(3);
        ch1.send("foo");
        ch2.close();
        fib.join();
    }

    @Test
    public void testFlatmapThreadToFiber() throws Exception {
        final Channel<Integer> ch1 = newChannel();

        Fiber fib = new Fiber("fiber", scheduler, new SuspendableRunnable() {
            @Override
            public void run() throws SuspendExecution, InterruptedException {
                ReceivePort<Integer> ch = Channels.flatmap(ch1, new Function<Integer, ReceivePort<Integer>>() {
                    @Override
                    public ReceivePort<Integer> apply(Integer x) {
                        if (x == 3)
                            return null;
                        if (x % 2 == 0)
                            return Channels.toReceivePort(Arrays.asList(new Integer[]{x * 10, x * 100, x * 1000}));
                        else
                            return Channels.singletonReceivePort(x);
                    }
                });

                assertThat(ch.receive(), is(1));
                assertThat(ch.receive(), is(20));
                assertThat(ch.receive(), is(200));
                assertThat(ch.receive(), is(2000));
                assertThat(ch.receive(), is(40));
                assertThat(ch.receive(), is(400));
                assertThat(ch.receive(), is(4000));
                assertThat(ch.receive(), is(5));
                assertThat(ch.receive(), is(nullValue()));
                assertThat(ch.isClosed(), is(true));
            }
        }).start();

        Strand.sleep(50);
        ch1.send(1);
        ch1.send(2);
        ch1.send(3);
        ch1.send(4);
        ch1.send(5);
        ch1.close();
        fib.join();
    }

    @Test
    public void testFlatmapWithTimeoutsThreadToFiber() throws Exception {
        final Channel<Integer> ch1 = newChannel();

        Fiber fib = new Fiber("fiber", scheduler, new SuspendableRunnable() {
            @Override
            public void run() throws SuspendExecution, InterruptedException {
                ReceivePort<Integer> ch = Channels.flatmap(ch1, new Function<Integer, ReceivePort<Integer>>() {
                    @Override
                    public ReceivePort<Integer> apply(Integer x) {
                        if (x == 3)
                            return null;
                        if (x % 2 == 0)
                            return Channels.toReceivePort(Arrays.asList(new Integer[]{x * 10, x * 100, x * 1000}));
                        else
                            return Channels.singletonReceivePort(x);
                    }
                });

                assertThat(ch.receive(), is(1));
                assertThat(ch.receive(30, TimeUnit.MILLISECONDS), is(nullValue()));
                assertThat(ch.receive(40, TimeUnit.MILLISECONDS), is(20));
                assertThat(ch.receive(), is(200));
                assertThat(ch.receive(), is(2000));
                assertThat(ch.receive(), is(40));
                assertThat(ch.receive(), is(400));
                assertThat(ch.receive(), is(4000));
                assertThat(ch.receive(30, TimeUnit.MILLISECONDS), is(nullValue()));
                assertThat(ch.receive(40, TimeUnit.MILLISECONDS), is(5));
                assertThat(ch.receive(), is(nullValue()));
                assertThat(ch.isClosed(), is(true));
            }
        }).start();

        Strand.sleep(50);
        ch1.send(1);
        Strand.sleep(50);
        ch1.send(2);
        ch1.send(3);
        ch1.send(4);
        Strand.sleep(50);
        ch1.send(5);
        ch1.close();
        fib.join();
    }

    @Test
    public void testFiberTransform1() throws Exception {
        final Channel<Integer> in = newChannel();
        final Channel<Integer> out = newChannel();

        Channels.fiberTransform(in, out, new SuspendableAction2<ReceivePort<Integer>, SendPort<Integer>>() {

            @Override
            public void call(ReceivePort<Integer> in, SendPort<Integer> out) throws SuspendExecution, InterruptedException {
                Integer x;
                while((x = in.receive()) != null) {
                    if(x % 2 == 0)
                        out.send(x * 10);
                }
                out.send(1234);
                out.close();
            }
        });
        
        Fiber fib1 = new Fiber("fiber", scheduler, new SuspendableRunnable() {
            @Override
            public void run() throws SuspendExecution, InterruptedException {
                assertThat(out.receive(), equalTo(20));
                assertThat(out.receive(), equalTo(40));
                assertThat(out.receive(), equalTo(1234));
                assertThat(out.receive(), is(nullValue()));
            }
        }).start();

        Fiber fib2 = new Fiber("fiber", scheduler, new SuspendableRunnable() {
            @Override
            public void run() throws SuspendExecution, InterruptedException {
                Strand.sleep(50);
                in.send(1);
                in.send(2);
                Strand.sleep(50);
                in.send(3);
                in.send(4);
                in.send(5);
                in.close();
            }
        }).start();

        fib1.join();
        fib2.join();
    }
}
