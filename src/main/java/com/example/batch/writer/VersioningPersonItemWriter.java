package com.example.batch.writer;

import com.example.batch.model.Person;
import com.example.batch.repository.PersonRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.batch.item.Chunk;
import org.springframework.batch.item.ItemWriter;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

@Component
public class VersioningPersonItemWriter implements ItemWriter<Person> {
    private static final Logger log = LoggerFactory.getLogger(VersioningPersonItemWriter.class);

    private final PersonRepository personRepository;

    public VersioningPersonItemWriter(PersonRepository personRepository) {
        this.personRepository = personRepository;
    }

    @Override
    @Transactional
    public void write(Chunk<? extends Person> chunk) throws Exception {
        for (Person p : chunk) {
            upsertVersioned(p);
        }
    }

    private void upsertVersioned(Person p) {
        try {
            var currentOpt = personRepository.findCurrentByEmail(p.getEmail());
            if (currentOpt.isEmpty()) {
                personRepository.insert(p, 1);
                return;
            }
            var current = currentOpt.get();
            boolean same = equalsNullable(current.getFirstName(), p.getFirstName())
                    && equalsNullable(current.getLastName(), p.getLastName())
                    && equalsNullable(current.getEmail(), p.getEmail())
                    && equalsNullable(current.getAge(), p.getAge());
            if (same) {
                // No change; keep current version
                return;
            }
            // Update: mark old not current and insert new version+1
            personRepository.markAllNotCurrent(p.getEmail());
            int nextVersion = (current.getVersion() == null ? 1 : current.getVersion()) + 1;
            personRepository.insert(p, nextVersion);
        } catch (DataAccessException e) {
            log.error("Failed to upsert person with email {}", p.getEmail(), e);
            throw e;
        }
    }

    private static boolean equalsNullable(Object a, Object b) {
        if (a == null) return b == null;
        return a.equals(b);
    }
}
