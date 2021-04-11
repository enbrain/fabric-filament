package net.fabricmc.filament.task;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import javax.inject.Inject;

import cuchaz.enigma.ProgressListener;
import cuchaz.enigma.translation.mapping.EntryMapping;
import cuchaz.enigma.translation.mapping.serde.MappingFileNameFormat;
import cuchaz.enigma.translation.mapping.serde.MappingParseException;
import cuchaz.enigma.translation.mapping.serde.MappingSaveParameters;
import cuchaz.enigma.translation.mapping.serde.enigma.EnigmaMappingsReader;
import cuchaz.enigma.translation.mapping.tree.EntryTree;
import cuchaz.enigma.translation.representation.entry.Entry;
import cuchaz.enigma.translation.representation.entry.LocalVariableEntry;
import org.gradle.api.DefaultTask;
import org.gradle.api.GradleException;
import org.gradle.api.file.DirectoryProperty;
import org.gradle.api.file.FileType;
import org.gradle.api.logging.Logger;
import org.gradle.api.logging.Logging;
import org.gradle.api.tasks.InputDirectory;
import org.gradle.api.tasks.TaskAction;
import org.gradle.work.ChangeType;
import org.gradle.work.FileChange;
import org.gradle.work.Incremental;
import org.gradle.work.InputChanges;
import org.gradle.workers.WorkAction;
import org.gradle.workers.WorkParameters;
import org.gradle.workers.WorkQueue;
import org.gradle.workers.WorkerExecutor;

import net.fabricmc.filament.util.FileUtil;

public abstract class MappingLintTask extends DefaultTask {
	private final DirectoryProperty mappingDirectory = getProject().getObjects().directoryProperty();

	@Incremental
	@InputDirectory
	public DirectoryProperty getMappingDirectory() {
		return mappingDirectory;
	}

	public MappingLintTask() {
		// Ignore outputs for up-to-date checks as there aren't any (so only inputs are checked)
		getOutputs().upToDateWhen(task -> true);
	}

	@Inject
	protected abstract WorkerExecutor getWorkerExecutor();

	@TaskAction
	public void run(InputChanges changes) throws IOException {
		Path directory = mappingDirectory.getAsFile().get().toPath();
		Path tempMappingDirectory = Files.createTempDirectory("mappingLintMappings");
		Files.createDirectories(tempMappingDirectory);

		for (FileChange change : changes.getFileChanges(mappingDirectory)) {
			if (change.getChangeType() != ChangeType.REMOVED && change.getFileType() == FileType.FILE) {
				Path targetPath = tempMappingDirectory.resolve(directory.relativize(change.getFile().getAbsoluteFile().toPath()));
				Files.createDirectories(targetPath.getParent());
				Files.copy(change.getFile().toPath(), targetPath);
			}
		}

		WorkQueue workQueue = getWorkerExecutor().noIsolation();
		workQueue.submit(LintAction.class, parameters -> parameters.getMappingDirectory().set(tempMappingDirectory.toFile()));
		workQueue.await();

		// Clean up and delete the temp directory
		FileUtil.deleteDirectory(tempMappingDirectory.toFile());
	}

	private static String getFullName(EntryTree<EntryMapping> mappings, Entry<?> entry) {
		String name = mappings.get(entry) != null ? mappings.get(entry).getTargetName() : entry.getName();

		if (entry.getParent() != null) {
			name = getFullName(mappings, entry.getParent()) + '.' + name;
		}

		return name;
	}

	public interface LintParameters extends WorkParameters {
		DirectoryProperty getMappingDirectory();
	}

	public abstract static class LintAction implements WorkAction<LintParameters> {
		private static final Logger LOGGER = Logging.getLogger(LintAction.class);

		@Inject
		public LintAction() {
		}

		@Override
		public void execute() {
			try {
				var saveParameters = new MappingSaveParameters(MappingFileNameFormat.BY_DEOBF);
				Path directory = getParameters().getMappingDirectory().getAsFile().get().toPath();
				EntryTree<EntryMapping> mappings = EnigmaMappingsReader.DIRECTORY.read(directory, ProgressListener.none(), saveParameters);
				List<String> errors = new ArrayList<>();

				mappings.getAllEntries().parallel().forEach(entry -> {
					List<String> localErrors = new ArrayList<>();

					if (entry instanceof LocalVariableEntry && ((LocalVariableEntry) entry).isArgument()) {
						if (((LocalVariableEntry) entry).getParent() != null) {
							String methodName = ((LocalVariableEntry) entry).getParent().getName();

							if (methodName != null && methodName.equals("equals")) {
								String paramName = mappings.get(entry).getTargetName();

								if (paramName != null && !paramName.equals("o")) {
									localErrors.add("parameter of equals method should be 'o'");
								}
							}
						}
					}

					if (!localErrors.isEmpty()) {
						String name = getFullName(mappings, entry);

						for (String error : localErrors) {
							errors.add(name + ": " + error);
						}
					}
				});

				if (!errors.isEmpty()) {
					for (String error : errors) {
						LOGGER.error("lint: {}", error);
					}

					throw new GradleException("Found " + errors.size() + " mapping errors! See the log for details.");
				}
			} catch (IOException | MappingParseException e) {
				throw new GradleException("Could not read and parse mappings", e);
			}
		}
	}
}
