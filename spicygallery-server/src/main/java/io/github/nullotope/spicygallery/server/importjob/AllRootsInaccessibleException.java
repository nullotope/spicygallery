package io.github.nullotope.spicygallery.server.importjob;

import java.util.List;

/** No configured library root is accessible — import start refused (spec §6.1, 400). */
public class AllRootsInaccessibleException extends Exception {

    private final List<String> roots;

    public AllRootsInaccessibleException(List<String> names) {
        super("no accessible library root: " + String.join(", ", names));
        this.roots = List.copyOf(names);
    }

    public List<String> roots() {
        return roots;
    }
}
