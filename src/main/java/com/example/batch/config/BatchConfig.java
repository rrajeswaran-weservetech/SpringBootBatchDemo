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
import org.springframework.batch.item.file.FlatFileItemWriter;
import org.springframework.batch.item.file.builder.FlatFileItemWriterBuilder;
import org.springframework.batch.item.file.transform.BeanWrapperFieldExtractor;
import org.springframework.batch.item.file.transform.DelimitedLineAggregator;
import com.example.batch.writer.VersioningPersonItemWriter;
import com.example.batch.repository.PersonRepository;
import org.springframework.web.client.RestClient;
import com.example.batch.reader.RestPagedPersonItemReader;
import org.springframework.batch.item.file.FlatFileItemReader;
import org.springframework.batch.item.file.LineCallbackHandler;
import org.springframework.batch.item.file.builder.FlatFileItemReaderBuilder;
import org.springframework.batch.item.file.mapping.BeanWrapperFieldSetMapper;
import org.springframework.batch.item.file.mapping.DefaultLineMapper;
import org.springframework.batch.item.file.transform.DelimitedLineTokenizer;
import org.springframework.batch.item.support.SynchronizedItemStreamReader;
import org.springframework.batch.item.support.SynchronizedItemStreamWriter;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.PathResource;
import org.springframework.core.task.SimpleAsyncTaskExecutor;
import org.springframework.core.task.TaskExecutor;
import org.springframework.transaction.PlatformTransactionManager;

import javax.sql.DataSource;

@Configuration
@EnableConfigurationProperties({BatchProperties.class, FileUploadProperties.class, FileOutputProperties.class})
public class BatchConfig {

    @Bean
    public RestClient restClient() {
        return RestClient.create();
    }

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

    @Bean(name = "batchTaskExecutor")
    public TaskExecutor taskExecutor(BatchProperties properties) {
        SimpleAsyncTaskExecutor executor = new SimpleAsyncTaskExecutor("batch-exec-");
        executor.setConcurrencyLimit(properties.getConcurrency().getThreads());
        return executor;
    }

    @Bean
    public Step csvToDbStep(JobRepository jobRepository,
                            PlatformTransactionManager transactionManager,
                            SynchronizedItemStreamReader<Person> synchronizedReader,
                            @org.springframework.beans.factory.annotation.Qualifier("personProcessor") ItemProcessor<Person, Person> personProcessor,
                            VersioningPersonItemWriter personWriter,
                            BatchProperties properties,
                            @org.springframework.beans.factory.annotation.Qualifier("batchTaskExecutor") TaskExecutor taskExecutor,
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
                .build();
        return step;
    }

    @Bean
    @StepScope
    public RestPagedPersonItemReader restPersonReader(RestClient restClient,
                                                      BatchProperties properties) {
        String baseUrl = properties.getRest().getBaseUrl();
        int size = properties.getRest().getPageSize();
        return new RestPagedPersonItemReader(restClient, baseUrl, size);
    }

    @Bean
    public ItemProcessor<Person, Person> personMatchProcessor(com.example.batch.repository.PersonRepository repo) {
        return restItem -> {
            if (restItem == null || restItem.getEmail() == null) return null;
            var opt = repo.findCurrentByEmail(restItem.getEmail());
            if (opt.isEmpty()) return null;
            var cur = opt.get();
            boolean same = eq(cur.getFirstName(), restItem.getFirstName())
                    && eq(cur.getLastName(), restItem.getLastName())
                    && eq(cur.getEmail(), restItem.getEmail())
                    && eq(cur.getAge(), restItem.getAge());
            return same ? restItem : null;
        };
    }

    private static boolean eq(Object a, Object b) { return (a == null ? b == null : a.equals(b)); }

    @Bean
    @StepScope
    public FlatFileItemWriter<Person> matchCsvWriter(@Value("#{jobParameters['outFile']}") String outFile) {
        BeanWrapperFieldExtractor<Person> extractor = new BeanWrapperFieldExtractor<>();
        extractor.setNames(new String[]{"firstName","lastName","email","age"});
        DelimitedLineAggregator<Person> aggregator = new DelimitedLineAggregator<>();
        aggregator.setDelimiter(",");
        aggregator.setFieldExtractor(extractor);
        return new FlatFileItemWriterBuilder<Person>()
                .name("matchCsvWriter")
                .resource(new PathResource(outFile))
                .lineAggregator(aggregator)
                .headerCallback(writer -> writer.write("firstName,lastName,email,age"))
                .append(false)
                .build();
    }

    @Bean
    public Step restCompareStep(JobRepository jobRepository,
                                PlatformTransactionManager transactionManager,
                                RestPagedPersonItemReader restPersonReader,
                                @org.springframework.beans.factory.annotation.Qualifier("personMatchProcessor") ItemProcessor<Person, Person> personMatchProcessor,
                                FlatFileItemWriter<Person> matchCsvWriter,
                                BatchProperties properties,
                                @org.springframework.beans.factory.annotation.Qualifier("batchTaskExecutor") TaskExecutor taskExecutor) {
        return new StepBuilder("restCompareStep", jobRepository)
                .<Person, Person>chunk(properties.getChunkSize(), transactionManager)
                .reader(restPersonReader)
                .processor(personMatchProcessor)
                .writer(matchCsvWriter)
                .taskExecutor(taskExecutor)
                .build();
    }

    @Bean
    public Job importPersonJob(JobRepository jobRepository,
                               @org.springframework.beans.factory.annotation.Qualifier("csvToDbStep") Step csvToDbStep,
                               @org.springframework.beans.factory.annotation.Qualifier("restCompareStep") Step restCompareStep) {
        return new JobBuilder("importPersonJob", jobRepository)
                .incrementer(new RunIdIncrementer())
                .start(csvToDbStep)
                .next(restCompareStep)
                .build();
    }
}
