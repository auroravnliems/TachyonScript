package dev.tachyonscript.benchmarks;

import dev.tachyonscript.api.natives.Arguments;
import dev.tachyonscript.platform.paper.AdventureTextService;
import dev.tachyonscript.runtime.spi.MessageTemplate;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Fork;
import org.openjdk.jmh.annotations.Measurement;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.Warmup;

import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * Building one chat message with two runtime values: TachyonScript's pre-parsed template
 * against parsing MiniMessage per message (with safe placeholders, and by string
 * concatenation, which is also unsafe because values are parsed as tags).
 */
@State(Scope.Thread)
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.NANOSECONDS)
@Warmup(iterations = 5, time = 1)
@Measurement(iterations = 5, time = 1)
@Fork(2)
public class TemplateBenchmark {

    private final MiniMessage miniMessage = MiniMessage.miniMessage();
    private MessageTemplate template;
    private Values values;
    private String name;
    private String coins;

    /** Arguments of one render. */
    private record Values(Object first, Object second) implements Arguments {
        @Override
        public int count() {
            return 2;
        }

        @Override
        public Object getRef(int index) {
            return index == 0 ? first : second;
        }

        @Override
        public int getInt(int index) {
            throw new UnsupportedOperationException();
        }

        @Override
        public long getLong(int index) {
            throw new UnsupportedOperationException();
        }

        @Override
        public float getFloat(int index) {
            throw new UnsupportedOperationException();
        }

        @Override
        public double getDouble(int index) {
            throw new UnsupportedOperationException();
        }

        @Override
        public boolean getBool(int index) {
            throw new UnsupportedOperationException();
        }
    }

    @Setup
    public void setUp() {
        // "<gold>[Shop]</gold> <gray>{player.name} bought <white>{amount}</white> diamonds."
        template = new AdventureTextService().compile(List.of(
                "<gold>[Shop]</gold> <gray>", " bought <white>", "</white> diamonds."));
        name = "Steve";
        coins = "64";
        values = new Values(name, coins);
    }

    @Benchmark
    public Object preParsedTemplate() {
        return template.render(values);
    }

    @Benchmark
    public Component miniMessageWithPlaceholders() {
        return miniMessage.deserialize("<gold>[Shop]</gold> <gray><name> bought <white><amount></white> diamonds.",
                Placeholder.unparsed("name", name), Placeholder.unparsed("amount", coins));
    }

    @Benchmark
    public Component miniMessageConcatenated() {
        return miniMessage.deserialize("<gold>[Shop]</gold> <gray>" + name + " bought <white>" + coins
                + "</white> diamonds.");
    }
}
