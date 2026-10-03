package at.struct.cdi.performance.jmh;

import jakarta.enterprise.context.control.RequestContextController;
import jakarta.enterprise.event.Event;
import jakarta.enterprise.inject.se.SeContainer;
import jakarta.enterprise.inject.se.SeContainerInitializer;

import java.util.concurrent.TimeUnit;

import at.struct.cdi.performance.beans.ApplicationScopedHolder;
import at.struct.cdi.performance.beans.ClassInterceptedBean;
import at.struct.cdi.performance.beans.MethodInterceptedBean;
import at.struct.cdi.performance.beans.SimpleApplicationScopedBeanWithoutInterceptor;
import at.struct.cdi.performance.beans.SimpleRequestScopedBeanWithoutInterceptor;
import at.struct.cdi.performance.events.MySimpleEvent;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Level;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.TearDown;
import org.openjdk.jmh.infra.Blackhole;

/**
 * JMH version of the scenarios in CdiPerformanceTest.
 * The CDI implementation under test is whatever is on the classpath (Maven profile OWB or Weld).
 * Unlike the original loop based test, results are consumed by a {@link Blackhole}
 * so the JIT cannot eliminate the proxy invocations.
 */
@BenchmarkMode(Mode.Throughput)
@OutputTimeUnit(TimeUnit.MICROSECONDS)
public class CdiBenchmark
{
    @State(Scope.Benchmark)
    public static class Container
    {
        SeContainer container;
        SimpleApplicationScopedBeanWithoutInterceptor appScoped;
        SimpleApplicationScopedBeanWithoutInterceptor injectedAppScoped;
        SimpleRequestScopedBeanWithoutInterceptor requestScoped;
        ClassInterceptedBean classIntercepted;
        MethodInterceptedBean methodIntercepted;
        Event<MySimpleEvent> event;
        MySimpleEvent simpleEvent = new MySimpleEvent();

        @Setup(Level.Trial)
        public void boot()
        {
            container = SeContainerInitializer.newInstance().initialize();
            appScoped = container.select(SimpleApplicationScopedBeanWithoutInterceptor.class).get();
            injectedAppScoped = container.select(ApplicationScopedHolder.class).get().getSimpleBeanWithoutInterceptor();
            requestScoped = container.select(SimpleRequestScopedBeanWithoutInterceptor.class).get();
            classIntercepted = container.select(ClassInterceptedBean.class).get();
            methodIntercepted = container.select(MethodInterceptedBean.class).get();
            event = container.getBeanManager().getEvent().select(MySimpleEvent.class);
        }

        @TearDown(Level.Trial)
        public void shutdown()
        {
            container.close();
        }
    }

    /**
     * Activates the request context on each benchmark thread.
     */
    @State(Scope.Thread)
    public static class RequestContext
    {
        RequestContextController controller;

        @Setup(Level.Iteration)
        public void activate(Container c)
        {
            controller = c.container.select(RequestContextController.class).get();
            controller.activate();
        }

        @TearDown(Level.Iteration)
        public void deactivate()
        {
            controller.deactivate();
        }
    }

    @Benchmark
    public void baselinePlainObject(Blackhole bh)
    {
        bh.consume(PLAIN.theMeaningOfLife());
    }

    private static final SimpleApplicationScopedBeanWithoutInterceptor PLAIN = new SimpleApplicationScopedBeanWithoutInterceptor();

    @Benchmark
    public void applicationScoped(Container c, Blackhole bh)
    {
        bh.consume(c.appScoped.theMeaningOfLife());
    }

    @Benchmark
    public void applicationScopedInjected(Container c, Blackhole bh)
    {
        bh.consume(c.injectedAppScoped.theMeaningOfLife());
    }

    @Benchmark
    public void requestScoped(Container c, RequestContext rc, Blackhole bh)
    {
        bh.consume(c.requestScoped.theMeaningOfLife());
    }

    @Benchmark
    public void classIntercepted(Container c, Blackhole bh)
    {
        bh.consume(c.classIntercepted.getMeaningOfLife());
    }

    @Benchmark
    public void methodIntercepted(Container c, Blackhole bh)
    {
        bh.consume(c.methodIntercepted.getMeaningOfLife());
    }

    @Benchmark
    public void methodNotIntercepted(Container c, Blackhole bh)
    {
        bh.consume(c.methodIntercepted.getMeaningOfHalfLife());
    }

    @Benchmark
    public void fireEvent(Container c)
    {
        c.event.fire(c.simpleEvent);
    }

    /**
     * Container boot + shutdown time.
     */
    @Benchmark
    @BenchmarkMode(Mode.SingleShotTime)
    @OutputTimeUnit(TimeUnit.MILLISECONDS)
    public void bootAndShutdown(Blackhole bh)
    {
        try (SeContainer container = SeContainerInitializer.newInstance().initialize())
        {
            bh.consume(container.select(ApplicationScopedHolder.class).get().getSimpleBeanWithoutInterceptor().theMeaningOfLife());
        }
    }
}
