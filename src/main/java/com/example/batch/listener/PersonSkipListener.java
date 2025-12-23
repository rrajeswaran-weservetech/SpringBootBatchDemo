package com.example.batch.listener;

import com.example.batch.model.Person;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.batch.core.SkipListener;
import org.springframework.stereotype.Component;

/**
 * Listener to track and log skipped records during batch processing.
 * Logs are written to logs/skipped-records.log
 */
@Component
public class PersonSkipListener implements SkipListener<Person, Person> {

    private static final Logger log = LoggerFactory.getLogger(PersonSkipListener.class);
    private static final Logger skippedLog = LoggerFactory.getLogger("SKIPPED_RECORDS");

    @Override
    public void onSkipInRead(Throwable t) {
        log.error("Skipped record during READ phase", t);
        skippedLog.error("READ_PHASE_SKIP: {}", t.getMessage());
    }

    @Override
    public void onSkipInProcess(Person item, Throwable t) {
        log.error("Skipped record during PROCESS phase: Person={}", item, t);
        skippedLog.error("PROCESS_PHASE_SKIP: firstName={}, lastName={}, email={}, age={} | Reason: {}",
                item.getFirstName(),
                item.getLastName(),
                item.getEmail(),
                item.getAge(),
                t.getMessage());
    }

    @Override
    public void onSkipInWrite(Person item, Throwable t) {
        log.error("Skipped record during WRITE phase: Person={}", item, t);
        skippedLog.error("WRITE_PHASE_SKIP: firstName={}, lastName={}, email={}, age={} | Reason: {}",
                item.getFirstName(),
                item.getLastName(),
                item.getEmail(),
                item.getAge(),
                t.getMessage());
    }
}
