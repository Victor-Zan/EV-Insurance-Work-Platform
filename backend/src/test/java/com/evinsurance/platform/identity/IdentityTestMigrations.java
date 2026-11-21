package com.evinsurance.platform.identity;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.util.Objects;

/** All formal migrations plus the original identity fixture; excludes unrelated local map demonstrations. */
final class IdentityTestMigrations {
    private static final String SEED=seed();
    private IdentityTestMigrations() {}
    static String[] locations() { return new String[]{"classpath:db/migration",SEED}; }
    private static String seed() {
        try {
            var directory=Files.createTempDirectory("identity-foundation-");directory.toFile().deleteOnExit();
            var file=directory.resolve("V2.1__development_foundation.sql");
            try(var input=IdentityTestMigrations.class.getResourceAsStream("/db/dev/V2.1__development_foundation.sql")) {
                Files.copy(Objects.requireNonNull(input),file);
            }
            file.toFile().deleteOnExit();return "filesystem:"+directory;
        } catch(IOException error) { throw new UncheckedIOException(error); }
    }
}
