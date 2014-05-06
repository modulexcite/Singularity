package com.hubspot.singularity.s3uploader;

import java.io.IOException;
import java.nio.file.FileSystem;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardWatchEventKinds;
import java.nio.file.WatchEvent.Kind;
import java.util.Collections;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;

import org.jets3t.service.S3Service;
import org.jets3t.service.impl.rest.httpclient.RestS3Service;
import org.jets3t.service.security.AWSCredentials;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.common.base.Optional;
import com.google.common.base.Predicate;
import com.google.common.base.Throwables;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.Iterables;
import com.google.common.collect.Maps;
import com.google.common.collect.Sets;
import com.google.common.util.concurrent.ThreadFactoryBuilder;
import com.google.inject.Inject;
import com.google.inject.name.Named;
import com.hubspot.mesos.JavaUtils;
import com.hubspot.singularity.runner.base.config.SingularityRunnerBaseModule;
import com.hubspot.singularity.runner.base.shared.S3UploadMetadata;
import com.hubspot.singularity.runner.base.shared.SingularityDriver;
import com.hubspot.singularity.runner.base.shared.WatchServiceHelper;
import com.hubspot.singularity.s3uploader.config.SingularityS3UploaderConfiguration;

public class SingularityS3UploaderDriver extends WatchServiceHelper implements SingularityDriver {

  private final static Logger LOG = LoggerFactory.getLogger(SingularityS3UploaderDriver.class);

  private final SingularityS3UploaderConfiguration configuration;
  private final ScheduledExecutorService scheduler;
  private final ObjectMapper objectMapper;
  private final Map<S3UploadMetadata, SingularityS3Uploader> metadataToUploader;
  private final Map<SingularityS3Uploader, Long> uploaderLastHadFilesAt;
  private final Lock runLock;
  private final ExecutorService executorService;
  private final FileSystem fileSystem;
  private final S3Service s3Service;
  private ScheduledFuture<?> future;
  
  @Inject
  public SingularityS3UploaderDriver(SingularityS3UploaderConfiguration configuration, @Named(SingularityRunnerBaseModule.JSON_MAPPER) ObjectMapper objectMapper) {
    super(configuration.getPollForShutDownMillis(), configuration.getS3MetadataDirectory(), ImmutableList.of(StandardWatchEventKinds.ENTRY_CREATE, StandardWatchEventKinds.ENTRY_DELETE));

    this.fileSystem = FileSystems.getDefault();
    try {
      this.s3Service = new RestS3Service(new AWSCredentials(configuration.getS3AccessKey(), configuration.getS3SecretKey()));
    } catch (Throwable t) {
      throw Throwables.propagate(t);
    }

    this.configuration = configuration;
    this.objectMapper = objectMapper;

    this.metadataToUploader = Maps.newHashMap();
    this.uploaderLastHadFilesAt = Maps.newHashMap();
    
    this.runLock = new ReentrantLock();

    this.executorService = Executors.newCachedThreadPool(new ThreadFactoryBuilder().setNameFormat("SingularityS3Uploader-%d").build());
    this.scheduler = Executors.newScheduledThreadPool(1, new ThreadFactoryBuilder().setNameFormat("SingularityS3Driver-%d").build());
  }
  
  private void readInitialFiles() throws IOException {
    final long start = System.currentTimeMillis();
    LOG.info("Scanning for metadata files (*.{}) in {}", configuration.getS3MetadataSuffix(), configuration.getS3MetadataDirectory());
    
    int foundFiles = 0;
    
    for (Path file : JavaUtils.iterable(configuration.getS3MetadataDirectory())) {
      if (!isS3MetadataFile(file)) {
        continue;
      }
      
      if (handleNewS3Metadata(file)) {
        foundFiles++;
      }
    }
    
    LOG.info("Found {} file(s) in {}", foundFiles, JavaUtils.duration(start));
  }

  @Override
  public void startAndWait() {
    try {
      readInitialFiles();
    } catch (Throwable t) {
      throw Throwables.propagate(t);
    }
    
    future = this.scheduler.scheduleAtFixedRate(new Runnable() {

      @Override
      public void run() {
        final long start = System.currentTimeMillis();

        runLock.lock();
        
        if (isStopped()) {
          LOG.warn("Driver is stopped, not checking uploads");
          return;
        }

        int uploads = 0;
        final int uploaders = metadataToUploader.size();

        try {
          uploads = checkUploads();
        } catch (Throwable t) {
          LOG.error("Uncaught exception while checking {} upload(s)", uploaders, t);
        } finally {
          runLock.unlock();
          LOG.info("Uploaded {} from {} uploader(s) in {}", uploads, uploaders, JavaUtils.duration(start));
        }
      }
    }, configuration.getCheckUploadsEverySeconds(), configuration.getCheckUploadsEverySeconds(), TimeUnit.SECONDS);
    
    try {
      super.watch();
    } catch (Throwable t) {
      throw Throwables.propagate(t);
    }
  }

  @Override
  public void shutdown() {
    final long start = System.currentTimeMillis();
    LOG.info("Gracefully shutting down S3Uploader, this may take a few moments...");
    
    runLock.lock();
    try {
      if (!super.stop()) {
        LOG.info("Already shutting down, ignoring request");
        return;
      }
    } finally {
      runLock.unlock();
    }
    
    future.cancel(false);
    
    scheduler.shutdown();
    executorService.shutdown();
   
    LOG.info("Shut down in {}", JavaUtils.duration(start));
  }

  private int checkUploads() {
    if (metadataToUploader.isEmpty()) {
      return 0;
    }

    final Set<Path> filesToUpload = Collections.newSetFromMap(new ConcurrentHashMap<Path, Boolean>(metadataToUploader.size() * 2, 0.75f, metadataToUploader.size()));
    final Map<SingularityS3Uploader, Future<Integer>> futures = Maps.newHashMapWithExpectedSize(metadataToUploader.size());

    for (final SingularityS3Uploader uploader : metadataToUploader.values()) {
      futures.put(uploader, executorService.submit(new Callable<Integer>() {

        @Override
        public Integer call() {
          
          Integer returnValue = 0;
          try {
            returnValue = uploader.upload(filesToUpload);
          } catch (Throwable t) {
            LOG.error("Error while processing uploader {}", uploader, t);
          }
          return returnValue;
        }
      }));
    }

    LOG.info("Waiting on {} future(s)", futures.size());
    int totesUploads = 0;

    final long now = System.currentTimeMillis();
    final Set<SingularityS3Uploader> expiredUploaders = Sets.newHashSetWithExpectedSize(metadataToUploader.size());
    
    // TODO cancel/timeouts?
    for (Entry<SingularityS3Uploader, Future<Integer>> uploaderToFuture : futures.entrySet()) {
      final SingularityS3Uploader uploader = uploaderToFuture.getKey();
      try {
        final int foundFiles = uploaderToFuture.getValue().get();
        if (foundFiles == 0) {
          final long durationSinceLastFile = now - uploaderLastHadFilesAt.get(uploader);
          
          if (durationSinceLastFile > configuration.getStopCheckingAfterMillisWithoutNewFile()) {
            LOG.info("Expiring uploader {}", uploader);
            expiredUploaders.add(uploader);
          }
        } else {
          uploaderLastHadFilesAt.put(uploader, now);
        }
        totesUploads += foundFiles;
      } catch (Throwable t) {
        LOG.error("Waiting on future", t);
      }
    }
    
    for (SingularityS3Uploader expiredUploader : expiredUploaders) {
      metadataToUploader.remove(expiredUploader.getUploadMetadata());
      uploaderLastHadFilesAt.remove(expiredUploader);
      try {
        Files.delete(expiredUploader.getMetadataPath());
      } catch (IOException e) {
        LOG.warn("Couldn't delete {}", expiredUploader.getMetadataPath(), e);
      }
    }

    return totesUploads;
  }

  private boolean handleNewS3Metadata(Path filename) throws IOException {
    Optional<S3UploadMetadata> metadata = readS3UploadMetadata(filename);

    if (metadataToUploader.containsKey(metadata)) {
      LOG.info("Ignoring metadata {} from {} because there was already one present", metadata.get(), filename);
      return false;
    }
    
    try {
      SingularityS3Uploader uploader = new SingularityS3Uploader(s3Service, metadata.get(), fileSystem, filename.toAbsolutePath());
      
      LOG.info("Created new uploader {}", uploader);
      
      metadataToUploader.put(metadata.get(), uploader);
      uploaderLastHadFilesAt.put(uploader, System.currentTimeMillis());
      return true;
    } catch (Throwable t) {
      LOG.info("Ignoring metadata {} because uploader couldn't be created", metadata.get(), t);
      return false;
    }
  }
  
  @Override
  protected boolean processEvent(Kind<?> kind, final Path filename) throws IOException {
    if (!isS3MetadataFile(filename)) {
      return false;
    }

    runLock.lock();
    
    if (isStopped()) {
      LOG.warn("Driver is stopped, ignoring file watch event for {}", filename);
      return false;
    }

    try {
      if (kind.equals(StandardWatchEventKinds.ENTRY_DELETE)) {
        SingularityS3Uploader found = Iterables.find(metadataToUploader.values(), new Predicate<SingularityS3Uploader>() {
          @Override
          public boolean apply(SingularityS3Uploader input) {
            return input.getMetadataPath().equals(filename.toAbsolutePath());
          }
        });

        LOG.info("Found {} to match deleted path {}", found, filename);

        if (found != null) {
          metadataToUploader.remove(found.getUploadMetadata());
        }
      } else if (kind.equals(StandardWatchEventKinds.ENTRY_CREATE)) {
        return handleNewS3Metadata(filename);
      }

      return false;
    } finally {
      runLock.unlock();
    }
  }

  private Optional<S3UploadMetadata> readS3UploadMetadata(Path filename) throws IOException {
    byte[] s3MetadataBytes = Files.readAllBytes(configuration.getS3MetadataDirectory().resolve(filename));

    LOG.trace("Read {} bytes from {}", s3MetadataBytes.length, filename);

    try {
      S3UploadMetadata metadata = objectMapper.readValue(s3MetadataBytes, S3UploadMetadata.class);
      return Optional.of(metadata);
    } catch (Throwable t) {
      LOG.warn("File {} was not a valid s3 metadata", filename, t);
      return Optional.absent();
    }
  }

  private boolean isS3MetadataFile(Path filename) {
    if (!filename.toString().endsWith(configuration.getS3MetadataSuffix())) {
      LOG.trace("Ignoring a file {} without {} suffix", filename, configuration.getS3MetadataSuffix());
      return false;
    }

    return true;
  }

}