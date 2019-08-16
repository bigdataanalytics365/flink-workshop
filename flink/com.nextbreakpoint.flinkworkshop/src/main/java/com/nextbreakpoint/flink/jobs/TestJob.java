package com.nextbreakpoint.flink.jobs;

import com.nextbreakpoint.flink.common.StreamJob;
import com.nextbreakpoint.flink.sensor.SensorData;
import org.apache.flink.api.java.tuple.Tuple2;
import org.apache.flink.api.java.utils.ParameterTool;
import org.apache.flink.streaming.api.datastream.DataStream;
import org.apache.flink.streaming.api.datastream.DataStreamSource;
import org.apache.flink.streaming.api.environment.StreamExecutionEnvironment;
import org.apache.flink.streaming.api.functions.sink.PrintSinkFunction;
import org.apache.flink.streaming.api.functions.sink.SinkFunction;
import org.apache.flink.streaming.api.functions.source.SourceFunction;
import org.apache.flink.streaming.api.watermark.Watermark;
import org.joda.time.DateTime;
import org.joda.time.format.ISODateTimeFormat;

import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import static com.nextbreakpoint.flink.common.Constants.BUCKET_BASE_PATH;
import static java.lang.Integer.valueOf;

public class TestJob extends StreamJob {
    private final DataStream<SensorData> source;
    private final SinkFunction<SensorData> sink;

    public TestJob(
            StreamExecutionEnvironment env,
            String basePath,
            DataStream<SensorData> source,
            SinkFunction<SensorData> sink) {
        super(env, basePath);
        this.source = source;
        this.sink = sink;
    }

    public static void main(String[] args) throws Exception {
        final ParameterTool parameters = ParameterTool.fromArgs(args);

        final String bucketBasePath = getNonNullableParam(parameters, BUCKET_BASE_PATH);

        final StreamExecutionEnvironment env = StreamExecutionEnvironment.getExecutionEnvironment();

        final DataStreamSource<SensorData> source = env.addSource(new RandomSensorDataSource());

        final TestJob job = new TestJob(env, bucketBasePath, source, new PrintSinkFunction<>());

        job.enableCheckpointing(3600000);

        job.disableRestart();

        job.transform().run();
    }

    @Override
    public StreamJob transform() {
        source.addSink(sink);

        return this;
    }

    private static class RandomSensorDataSource implements SourceFunction<SensorData> {
        private static final List<Tuple2<Integer, UUID>> sensors = IntStream.range(0, 1)
                .mapToObj(i -> new Tuple2<>(i, UUID.randomUUID())).collect(Collectors.toList());

        private volatile boolean running = true;

        @Override
        public void run(SourceContext<SensorData> sourceContext) {
            running = true;

            double time = 0;

            for (;;) {
                final DateTime instant = new DateTime();

                final double currentTime = time;

                final List<SensorData> messages = sensors.stream()
                        .map(tuple -> new SensorData(tuple.f1.toString(), Math.sin(2 * Math.PI * (currentTime + tuple.f0)) + Math.random() / 100.0, ISODateTimeFormat.dateTime().print(instant)))
                        .collect(Collectors.toList());

                messages.forEach(message -> sourceContext.collectWithTimestamp(message, instant.getMillis()));

                sourceContext.emitWatermark(new Watermark(instant.getMillis()));

                sourceContext.markAsTemporarilyIdle();

                time += 10;

                try {
                    Thread.sleep(10000);
                } catch (InterruptedException e) {
                    break;
                }

                if (!running) {
                    break;
                }
            }
        }

        @Override
        public void cancel() {
            running = false;
        }
    }
}
