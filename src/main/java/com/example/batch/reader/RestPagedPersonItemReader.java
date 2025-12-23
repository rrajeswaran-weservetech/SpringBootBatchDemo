package com.example.batch.reader;

import com.example.batch.model.PageResponse;
import com.example.batch.model.Person;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.batch.item.ExecutionContext;
import org.springframework.batch.item.ItemStream;
import org.springframework.batch.item.ItemStreamReader;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.lang.Nullable;
import org.springframework.util.Assert;
import org.springframework.web.client.RestClient;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;

/**
 * A page-by-page REST reader that calls endpoints like:
 *   GET baseUrl?page={page}&size={size}
 * and expects a PageResponse<Person> JSON response containing:
 * {
 *   "content": [...],
 *   "page": 0,
 *   "size": 500,
 *   "totalElements": 11000,
 *   "totalPages": 22
 * }
 */
public class RestPagedPersonItemReader implements ItemStreamReader<Person>, ItemStream {
    private static final Logger log = LoggerFactory.getLogger(RestPagedPersonItemReader.class);

    private final RestClient restClient;
    private final String baseUrl;
    private final int pageSize;

    private int page = 0;
    private boolean finished = false;
    private Deque<Person> buffer = new ArrayDeque<>();
    private long totalElements = -1;

    public RestPagedPersonItemReader(RestClient restClient, String baseUrl, int pageSize) {
        Assert.notNull(restClient, "restClient must not be null");
        Assert.hasText(baseUrl, "baseUrl must not be empty");
        this.restClient = restClient;
        this.baseUrl = baseUrl;
        this.pageSize = pageSize;
    }

    @Override
    public void open(ExecutionContext executionContext) {
        // Restore state from execution context if available (for restart support)
        if (executionContext.containsKey("page")) {
            this.page = executionContext.getInt("page");
            this.finished = Boolean.parseBoolean(executionContext.getString("finished", "false"));
            log.info("Resuming RestPagedPersonItemReader from page {} for URL: {}", page, baseUrl);
        } else {
            // Initialize fresh state
            this.page = 0;
            this.finished = false;
            log.info("Opened RestPagedPersonItemReader for URL: {}", baseUrl);
        }
        this.buffer.clear();
        this.totalElements = -1;
    }

    @Override
    public void update(ExecutionContext executionContext) {
        // Save current state for restart capability
        executionContext.putInt("page", page);
        executionContext.putString("finished", String.valueOf(finished));
    }

    @Override
    public void close() {
        buffer.clear();
        log.info("Closed RestPagedPersonItemReader. Total pages read: {}", page);
    }

    @Override
    public synchronized @Nullable Person read() {
        if (finished) {
            return null;
        }

        if (buffer.isEmpty()) {
            fetchNextPage();
            if (buffer.isEmpty()) {
                finished = true;
                return null;
            }
        }

        return buffer.pollFirst();
    }

    private void fetchNextPage() {
        String url = String.format("%s?page=%d&size=%d", baseUrl, page, pageSize);
        log.debug("Fetching page {} from: {}", page, url);

        try {
            PageResponse<Person> response = restClient.get()
                    .uri(url)
                    .retrieve()
                    .body(new ParameterizedTypeReference<PageResponse<Person>>() {});

            if (response == null || response.getContent() == null || response.getContent().isEmpty()) {
                log.info("No more data available. Finished reading at page {}", page);
                finished = true;
                return;
            }

            List<Person> content = response.getContent();
            buffer.addAll(content);

            if (totalElements == -1) {
                totalElements = response.getTotalElements();
                log.info("Starting to read {} total elements across {} pages",
                        response.getTotalElements(), response.getTotalPages());
            }

            log.info("Fetched page {} with {} persons. Total progress: {}/{}",
                    page, content.size(),
                    (page * pageSize) + content.size(),
                    totalElements);

            page++;

        } catch (Exception e) {
            log.error("Error fetching page {} from URL: {}", page, url, e);
            finished = true;
            throw new RuntimeException("Failed to fetch data from REST endpoint: " + url, e);
        }
    }

    public long getTotalElements() {
        return totalElements;
    }
}
