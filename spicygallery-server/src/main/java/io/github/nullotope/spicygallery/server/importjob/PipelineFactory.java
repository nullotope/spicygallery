package io.github.nullotope.spicygallery.server.importjob;

import io.github.nullotope.spicygallery.importer.ImportPipeline;
import io.github.nullotope.spicygallery.importer.ImportReporter;

/** Creates the pipeline for one run; the service supplies the run's reporter. */
@FunctionalInterface
public interface PipelineFactory {

    ImportPipeline create(ImportReporter reporter);
}
