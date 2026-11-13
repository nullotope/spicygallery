package io.github.nullotope.spicygallery.server.concepts;

import java.util.List;

/**
 * Pure anchor math (spec §5): the component-wise mean of a concept's input vectors.
 * Never touches the DB and has no dependencies — the caller decides what an anchorless
 * concept (null result) means.
 */
public final class AnchorMath {

    private AnchorMath() {
    }

    /**
     * Component-wise mean of all non-null inputs. Order-independent, and exact for
     * duplicated inputs (the mean of {@code [v, v]} is {@code v}); null elements are
     * skipped. Returns {@code null} for a null, empty, or all-null list.
     *
     * @throws IllegalArgumentException when non-null inputs disagree on dimensions
     */
    public static float[] mean(List<float[]> inputs) {
        if (inputs == null) {
            return null;
        }
        float[] sum = null;
        int count = 0;
        for (float[] input : inputs) {
            if (input == null) {
                continue;
            }
            if (sum == null) {
                sum = new float[input.length];
            } else if (sum.length != input.length) {
                throw new IllegalArgumentException(
                        "mismatched vector dimensions: " + sum.length + " vs " + input.length);
            }
            for (int i = 0; i < input.length; i++) {
                sum[i] += input[i];
            }
            count++;
        }
        if (count == 0) {
            return null;
        }
        float[] mean = new float[sum.length];
        for (int i = 0; i < sum.length; i++) {
            mean[i] = sum[i] / count;
        }
        return mean;
    }
}
