package at.struct.cdi.performance.jmh;

import jakarta.enterprise.context.control.RequestContextController;
import jakarta.enterprise.inject.se.SeContainer;
import jakarta.enterprise.inject.se.SeContainerInitializer;

import java.util.concurrent.TimeUnit;

import at.struct.cdi.performance.beans.ApplicationScopedHolder;
import at.struct.cdi.performance.beans.MethodInterceptedBean;
import at.struct.cdi.performance.beans.SimpleApplicationScopedBeanWithoutInterceptor;
import at.struct.cdi.performance.beans.SimpleRequestScopedBeanWithoutInterceptor;
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
 * Client proxy invocations in a deployment <b>without any interceptor or decorator</b>
 * (bean discovery disabled, only the three plain beans are added, the NopInterceptor is not enabled).
 * {@link CdiBenchmark} is left unchanged for comparability with older results.
 *
 * {@link #applicationScopedInterceptionUsed} is the counterpart: the regular deployment (with the interceptor)
 * after an intercepted method has been invoked once, i.e. the state of a typical application.
 */
@BenchmarkMode(Mode.Throughput)
@OutputTimeUnit(TimeUnit.MICROSECONDS)
public class CdiNoInterceptorBenchmark
{
    @State(Scope.Benchmark)
    public static class PlainContainer
    {
        SeContainer container;
        SimpleApplicationScopedBeanWithoutInterceptor appScoped;
        SimpleApplicationScopedBeanWithoutInterceptor injectedAppScoped;
        SimpleRequestScopedBeanWithoutInterceptor requestScoped;

        @Setup(Level.Trial)
        public void boot()
        {
            container = SeContainerInitializer.newInstance()
                    .disableDiscovery()
                    .addBeanClasses(SimpleApplicationScopedBeanWithoutInterceptor.class,
                            SimpleRequestScopedBeanWithoutInterceptor.class,
                            ApplicationScopedHolder.class)
                    .initialize();
            appScoped = container.select(SimpleApplicationScopedBeanWithoutInterceptor.class).get();
            injectedAppScoped = container.select(ApplicationScopedHolder.class).get().getSimpleBeanWithoutInterceptor();
            requestScoped = container.select(SimpleRequestScopedBeanWithoutInterceptor.class).get();
        }

        @TearDown(Level.Trial)
        public void shutdown()
        {
            container.close();
        }
    }

    @State(Scope.Thread)
    public static class RequestContext
    {
        RequestContextController controller;

        @Setup(Level.Iteration)
        public void activate(PlainContainer c)
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

    @State(Scope.Benchmark)
    public static class InterceptionUsedContainer
    {
        SeContainer container;
        SimpleApplicationScopedBeanWithoutInterceptor appScoped;

        @Setup(Level.Trial)
        public void boot()
        {
            container = SeContainerInitializer.newInstance().initialize();
            appScoped = container.select(SimpleApplicationScopedBeanWithoutInterceptor.class).get();
            // invoke an intercepted method once, as any application using interceptors does
            container.select(MethodInterceptedBean.class).get().getMeaningOfLife();
        }

        @TearDown(Level.Trial)
        public void shutdown()
        {
            container.close();
        }
    }

    @Benchmark
    public void applicationScoped(PlainContainer c, Blackhole bh)
    {
        bh.consume(c.appScoped.theMeaningOfLife());
    }

    @Benchmark
    public void applicationScopedInjected(PlainContainer c, Blackhole bh)
    {
        bh.consume(c.injectedAppScoped.theMeaningOfLife());
    }

    @Benchmark
    public void requestScoped(PlainContainer c, RequestContext rc, Blackhole bh)
    {
        bh.consume(c.requestScoped.theMeaningOfLife());
    }

    @Benchmark
    public void applicationScopedInterceptionUsed(InterceptionUsedContainer c, Blackhole bh)
    {
        bh.consume(c.appScoped.theMeaningOfLife());
    }
}
