package com.example.batch.config;

import com.example.batch.listener.PersonSkipListener;
import com.example.batch.model.Person;
import org.springframework.batch.core.Job;
import org.springframework.batch.core.Step;
import org.springframework.batch.core.configuration.annotation.StepScope;
import org.springframework.batch.core.job.builder.JobBuilder;
import org.springframework.batch.core.launch.support.RunIdIncrementer;
import org.springframework.batch.core.repository.JobRepository;
import org.springframework.batch.core.step.builder.StepBuilder;
import org.springframework.batch.item.ItemProcessor;
import org.springframework.batch.item.database.BeanPropertyItemSqlParameterSourceProvider;
import org.springframework.batch.item.database.JdbcBatchItemWriter;
import org.springframework.batch.item.database.builder.JdbcBatchItemWriterBuilder;
import org.springframework.batch.item.file.FlatFileItemReader;
import org.springframework.batch.item.file.LineCallbackHandler;
import org.springframework.batch.item.file.builder.FlatFileItemReaderBuilder;
import org.springframework.batch.item.file.mapping.BeanWrapperFieldSetMapper;
import org.springframework.batch.item.file.mapping.DefaultLineMapper;
import org.springframework.batch.item.file.transform.DelimitedLineTokenizer;
import org.springframework.batch.item.support.SynchronizedItemStreamReader;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.task.SimpleAsyncTaskExecutor;
import org.springframework.core.task.TaskExecutor;
import org.springframework.transaction.PlatformTransactionManager;

import javax.sql.DataSource;

@Configuration
@EnableConfigurationProperties({BatchProperties.class, FileUploadProperties.class})
public class BatchConfig {

    @Bean
    @StepScope
    public FlatFileItemReader<Person> personReader(@Value("#{jobParameters['file']}") String file) {
        // Define a reader that maps CSV columns to Person fields
        FlatFileItemReader<Person> reader = new FlatFileItemReaderBuilder<Person>()
                .name("personReader")
                .resource(new FileSystemResource(file))
                .linesToSkip(1)
                .lineMapper(lineMapper())
                .build();

        // Add a header callback just for clarity (optional)
        reader.setSkippedLinesCallback((LineCallbackHandler) line -> {
            // header skipped
        });

        return reader;
    }


    private DefaultLineMapper<Person> lineMapper() {
        DefaultLineMapper<Person> lineMapper = new DefaultLineMapper<>();
        DelimitedLineTokenizer tokenizer = new DelimitedLineTokenizer();
        tokenizer.setDelimiter(",");
        tokenizer.setNames("firstName", "lastName", "email", "age");
        tokenizer.setQuoteCharacter('"');

        BeanWrapperFieldSetMapper<Person> fieldSetMapper = new BeanWrapperFieldSetMapper<>();
        fieldSetMapper.setTargetType(Person.class);

        lineMapper.setLineTokenizer(tokenizer);
        lineMapper.setFieldSetMapper(fieldSetMapper);
        return lineMapper;
    }

    @Bean
    @StepScope
    public SynchronizedItemStreamReader<Person> synchronizedReader(FlatFileItemReader<Person> personReader) {
        SynchronizedItemStreamReader<Person> syncReader = new SynchronizedItemStreamReader<>();
        syncReader.setDelegate(personReader);
        return syncReader;
    }

    @Bean
    public ItemProcessor<Person, Person> personProcessor() {
        return item -> {
            // Basic sanitization; skip invalid lines
            if (item.getEmail() == null || !item.getEmail().contains("@")) {
                return null; // filtered out
            }
            if (item.getFirstName() != null) item.setFirstName(item.getFirstName().trim());
            if (item.getLastName() != null) item.setLastName(item.getLastName().trim());
            if (item.getEmail() != null) item.setEmail(item.getEmail().trim().toLowerCase());
            return item;
        };
    }

    @Bean
    public JdbcBatchItemWriter<Person> personWriter(DataSource dataSource) {
        return new JdbcBatchItemWriterBuilder<Person>()
                .itemSqlParameterSourceProvider(new BeanPropertyItemSqlParameterSourceProvider<>())
                .sql("INSERT INTO dbo.persons (first_name, last_name, email, age) VALUES (:firstName, :lastName, :email, :age)")
                .dataSource(dataSource)
                .build();
    }

    @Bean
    public TaskExecutor taskExecutor(BatchProperties properties) {
        SimpleAsyncTaskExecutor executor = new SimpleAsyncTaskExecutor("batch-exec-");
        executor.setConcurrencyLimit(properties.getConcurrency().getThreads());
        return executor;
    }

    @Bean
    public Step csvToDbStep(JobRepository jobRepository,
                            PlatformTransactionManager transactionManager,
                            SynchronizedItemStreamReader<Person> synchronizedReader,
                            ItemProcessor<Person, Person> personProcessor,
                            JdbcBatchItemWriter<Person> personWriter,
                            BatchProperties properties,
                            TaskExecutor taskExecutor,
                            PersonSkipListener personSkipListener) {

        StepBuilder builder = new StepBuilder("csvToDbStep", jobRepository);
        var step = builder
                .<Person, Person>chunk(properties.getChunkSize(), transactionManager)
                .reader(synchronizedReader)
                .processor(personProcessor)
                .writer(personWriter)
                .faultTolerant()
                .skipLimit(100)
                .skip(Exception.class)
                .listener(personSkipListener)
                .taskExecutor(taskExecutor)
                .throttleLimit(properties.getConcurrency().getThreads())
                .build();
        return step;
    }

    @Bean
    public Job importPersonJob(JobRepository jobRepository, Step csvToDbStep) {
        return new JobBuilder("importPersonJob", jobRepository)
                .incrementer(new RunIdIncrementer())
                .start(csvToDbStep)
                .build();
    }
}
