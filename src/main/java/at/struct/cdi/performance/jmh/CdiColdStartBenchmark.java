package at.struct.cdi.performance.jmh;

import java.util.concurrent.TimeUnit;

import jakarta.enterprise.inject.se.SeContainer;
import jakarta.enterprise.inject.se.SeContainerInitializer;

import at.struct.cdi.performance.beans.ApplicationScopedHolder;
import at.struct.cdi.performance.beans.ClassInterceptedBean;
import at.struct.cdi.performance.beans.MethodInterceptedBean;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.infra.Blackhole;

/**
 * Run with no warmup, one single-shot measurement and multiple fresh JVM forks.
 * Includes first-use invoker generation, which warmed throughput/boot measurements can hide.
 */
@BenchmarkMode(Mode.SingleShotTime)
@OutputTimeUnit(TimeUnit.MILLISECONDS)
public class CdiColdStartBenchmark
{
    @Benchmark
    public void plainBootAndShutdown(Blackhole bh)
    {
        try (SeContainer container = SeContainerInitializer.newInstance().initialize())
        {
            bh.consume(container.select(ApplicationScopedHolder.class).get()
                    .getSimpleBeanWithoutInterceptor().theMeaningOfLife());
        }
    }

    @Benchmark
    public void interceptedBootAndShutdown(Blackhole bh)
    {
        try (SeContainer container = SeContainerInitializer.newInstance().initialize())
        {
            bh.consume(container.select(ClassInterceptedBean.class).get().getMeaningOfLife());
            bh.consume(container.select(MethodInterceptedBean.class).get().getMeaningOfLife());
        }
    }
}
