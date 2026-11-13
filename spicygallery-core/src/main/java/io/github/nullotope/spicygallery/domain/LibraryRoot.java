package io.github.nullotope.spicygallery.domain;

import java.nio.file.Path;

/**
 * One configured library root (spec §10.1). The {@code name} is the stable identity
 * key used in the database; renaming it in config orphans the library's rows.
 */
public record LibraryRoot(String name, Path root) {}
