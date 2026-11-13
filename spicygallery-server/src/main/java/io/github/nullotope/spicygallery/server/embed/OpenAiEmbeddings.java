package io.github.nullotope.spicygallery.server.embed;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.io.IOException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import org.springframework.http.MediaType;
import org.springframework.web.client.RestClient;

/**
 * OpenAI-compatible embeddings client (spec §3.2) — the ONLY class that knows the
 * multimodal wire shape. Image parts use the content-wrapper form verified against
 * llama.cpp in the Task 1 dimension probe:
 * {@code {"type":"content","content":[{"type":"image_url","image_url":{"url":"data:…"}}]}}.
 * A bare data-URI string would embed the URI as TEXT (probe trap) — never send one.
 * One HTTP request per {@link #embed} call; batching lives in EmbeddingService.
 */
public final class OpenAiEmbeddings implements EmbeddingProvider {

    private final String baseUrl;
    private final String model;
    private final String apiKey;
    private final RestClient http;

    public OpenAiEmbeddings(String baseUrl, String model, String apiKey, Duration timeout) {
        this.baseUrl = baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
        this.model = model;
        this.apiKey = apiKey == null || apiKey.isBlank() ? null : apiKey;
        this.http = RestClient.builder()
                .requestFactory(new org.springframework.http.client.SimpleClientHttpRequestFactory() {
                    {
                        setConnectTimeout((int) timeout.toMillis());
                        setReadTimeout((int) timeout.toMillis());
                    }
                })
                .build();
    }

    @Override
    public List<float[]> embed(List<EmbeddingInput> inputs) throws IOException {
        List<Object> input = new ArrayList<>(inputs.size());
        for (EmbeddingInput in : inputs) {
            input.add(in.image() != null ? imagePart(in.image(), in.mime()) : in.text());
        }
        EmbeddingsResponse response;
        try {
            var spec = http.post().uri(baseUrl + "/embeddings")
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(java.util.Map.of("model", model, "input", input));
            if (apiKey != null) {
                spec = spec.header("Authorization", "Bearer " + apiKey);
            }
            response = spec.retrieve().body(EmbeddingsResponse.class);
        } catch (RuntimeException e) {
            throw new IOException("embedding provider call failed: " + e.getMessage(), e);
        }
        if (response == null || response.data() == null) {
            throw new IOException("embedding provider returned no data");
        }
        return response.data().stream()
                .sorted(java.util.Comparator.comparingInt(Embedding::index))
                .map(d -> {
                    List<Double> v = d.embedding();
                    float[] f = new float[v.size()];
                    for (int i = 0; i < f.length; i++) {
                        f[i] = v.get(i).floatValue();
                    }
                    return f;
                })
                .toList();
    }

    private static Object imagePart(byte[] image, String mime) {
        String uri = "data:" + (mime == null ? "image/jpeg" : mime)
                + ";base64," + Base64.getEncoder().encodeToString(image);
        return java.util.Map.of(
                "type", "content",
                "content", List.of(java.util.Map.of(
                        "type", "image_url",
                        "image_url", java.util.Map.of("url", uri))));
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record EmbeddingsResponse(List<Embedding> data) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    record Embedding(int index, List<Double> embedding) {}
}
