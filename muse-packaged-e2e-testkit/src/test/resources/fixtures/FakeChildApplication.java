package fixtures;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;

public final class FakeChildApplication {
    private FakeChildApplication() {
    }

    public static void main(String[] args) throws Exception {
        String mode = args[0];
        System.out.println("jvm=" + System.getProperty("fixture.jvm", "unset") + "; mode=" + mode);
        if ("exit".equals(mode)) {
            System.out.println("synthetic child diagnostic");
            System.exit(17);
        }

        Path ready = Path.of(args[1]);
        if ("stubborn".equals(mode)) {
            Runtime.getRuntime().addShutdownHook(new Thread(() -> {
                try {
                    Thread.sleep(10_000);
                } catch (InterruptedException ignored) {
                    Thread.currentThread().interrupt();
                }
            }));
        }

        if ("descriptor".equals(mode)) {
            Path heldFile = Path.of(args[2]);
            Path closeCommand = Path.of(args[3]);
            try (InputStream ignored = Files.newInputStream(heldFile)) {
                Files.writeString(ready, "ready");
                while (!Files.exists(closeCommand)) {
                    Thread.sleep(10);
                }
            }
            while (true) {
                Thread.sleep(1_000);
            }
        }

        Files.writeString(ready, "ready");
        while (true) {
            Thread.sleep(1_000);
        }
    }
}
