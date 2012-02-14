package com.dynamo.bob;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.apache.commons.io.DirectoryWalker;
import org.apache.commons.io.FilenameUtils;

/**
 * Project abstraction. Contains input files, builder, tasks, etc
 * @author Christian Murray
 *
 */
public class Project {

    private IFileSystem fileSystem;
    private Map<String, Class<? extends Builder<?>>> extToBuilder = new HashMap<String, Class<? extends Builder<?>>>();
    private List<String> inputs = new ArrayList<String>();
    private ArrayList<Task<?>> tasks;
    private State state;
    private String buildDirectory = "build";
    private Map<String, String> options = new HashMap<String, String>();

    public Project(IFileSystem fileSystem) {
        this.fileSystem = fileSystem;
        this.fileSystem.setBuildDirectory(buildDirectory);
    }

    public Project(IFileSystem fileSystem, String buildDirectory) {
        this.buildDirectory = buildDirectory;
        this.fileSystem = fileSystem;
        this.fileSystem.setBuildDirectory(buildDirectory);
    }

    public String getBuildDirectory() {
        return buildDirectory;
    }

    /**
     * Scan package for builder classes
     * @param pkg package name to be scanned
     */
    @SuppressWarnings("unchecked")
    public void scanPackage(String pkg) {
        Set<String> classNames = ClassScanner.scan(pkg);
        for (String className : classNames) {
            try {
                Class<?> klass = Class.forName(className);
                BuilderParams params = klass.getAnnotation(BuilderParams.class);
                if (params != null) {
                    for (String inExt : params.inExts()) {
                        extToBuilder.put(inExt, (Class<? extends Builder<?>>) klass);
                    }
                }
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        }
    }

    private Task<?> doCreateTask(String input) {
        String ext = "." + FilenameUtils.getExtension(input);
        Class<? extends Builder<?>> builderClass = extToBuilder.get(ext);
        Builder<?> builder;
        if (builderClass != null) {
            try {
                builder = builderClass.newInstance();
                builder.setProject(this);
                IResource inputResource = fileSystem.get(input);
                Task<?> task = builder.create(inputResource);
                return task;
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        } else {
            logWarning("No builder for '%s' found", input);
        }
        return null;
    }

    /**
     * Create task from resource. Typically called from builder
     * that create intermediate output/input-files
     * @param input input resource
     * @return task
     */
    public Task<?> buildResource(IResource input) {
        Task<?> task = doCreateTask(input.getPath());
        if (task != null) {
            tasks.add(task);
        }
        return task;
    }

    private void createTasks() {
        tasks = new ArrayList<Task<?>>();
        for (String input : inputs) {
            Task<?> task = doCreateTask(input);
            if (task != null) {
                tasks.add(task);
            }
        }
    }

    private void logWarning(String fmt, Object... args) {
        System.err.println(String.format(fmt, args));
    }

    /**
     * Build the project
     * @return list of {@link TaskResult}. Only executed nodes are part of the list.
     * @throws IOException
     */
    public List<TaskResult> build() throws IOException {
        IResource stateResource = fileSystem.get(FilenameUtils.concat(buildDirectory, "state"));
        state = State.load(stateResource);
        createTasks();
        List<TaskResult> result = runTasks();
        state.save(stateResource);
        return result;
    }

    @SuppressWarnings({ "rawtypes", "unchecked" })
    private List<TaskResult> runTasks() throws IOException {

        // set of all completed tasks. The set includes both task run
        // in this session and task already completed (output already exists with correct signatures, see below)
        // the set also contains failed tasks
        Set<Task> completedTasks = new HashSet<Task>();

        // set of *all* possible output files
        Set<IResource> allOutputs = new HashSet<IResource>();
        for (Task<?> task : tasks) {
            allOutputs.addAll(task.getOutputs());
        }

        // the set of all output files generated
        // in this or previous session
        Set<IResource> completedOutputs = new HashSet<IResource>();

        List<TaskResult> result = new ArrayList<TaskResult>();

        // number of task completed. not the same as number of tasks run
        // this number is always incremented apart from when a task
        // is waiting for task(s) generating input to this task
        // TODO: verify that this scheme really is sound
        int completedCount = 0;

        while (completedCount < tasks.size()) {
            for (Task<?> task : tasks) {
                // deps are the task input files generated by another task not yet completed,
                // i.e. "solve" the dependency graph
                Set<IResource> deps = new HashSet<IResource>();
                deps.addAll(task.getInputs());
                deps.retainAll(allOutputs);
                deps.removeAll(completedOutputs);
                if (deps.size() > 0) {
                    // postpone task. dependent input not yet generated
                    continue;
                }

                ++completedCount;

                byte[] taskSignature = task.calculateSignature(this);

                // do all output files exist?
                boolean allOutputExists = true;
                for (IResource r : task.getOutputs()) {
                    if (!r.exists()) {
                        allOutputExists = false;
                        break;
                    }
                }

                // compare all task signature. current task signature between previous
                // signature from state on disk
                List<byte[]> outputSigs = new ArrayList<byte[]>();
                for (IResource r : task.getOutputs()) {
                    byte[] s = state.getSignature(r.getPath());
                    outputSigs.add(s);
                }
                boolean allSigsEquals = true;
                for (byte[] sig : outputSigs) {
                    if (!Arrays.equals(sig, taskSignature)) {
                        allSigsEquals = false;
                        break;
                    }
                }

                boolean shouldRun = (!allOutputExists || !allSigsEquals) && !completedTasks.contains(task);

                if (!shouldRun) {
                    completedTasks.add(task);
                    completedOutputs.addAll(task.getOutputs());
                    continue;
                }

                completedTasks.add(task);

                TaskResult taskResult = new TaskResult(task);
                result.add(taskResult);
                Builder builder = task.getBuilder();
                try {
                    builder.build(task);
                    taskResult.setReturnCode(0);
                    for (IResource r : task.getOutputs()) {
                        state.putSignature(r.getPath(), taskSignature);
                    }

                    for (IResource r : task.getOutputs()) {
                        if (!r.exists()) {
                            taskResult.setMessage(String.format("Output '%s' not found", r.getPath()));
                            taskResult.setReturnCode(50);
                        }
                    }
                    completedOutputs.addAll(task.getOutputs());

                } catch (CompileExceptionError e) {
                    taskResult.setReturnCode(e.getReturnCode());
                    taskResult.setMessage(e.getMessage());
                } catch (Throwable e) {
                    throw new RuntimeException(e);
                }
            }
        }
        return result;
    }

    /**
     * Set files to compile
     * @param inputs list of input files
     */
    public void setInputs(List<String> inputs) {
        this.inputs = new ArrayList<String>(inputs);
    }

    /**
     * Set option
     * @param key option key
     * @param value option value
     */
    public void setOption(String key, String value) {
        options.put(key, value);
    }

    /**
     * Get option
     * @param key key to get option for
     * @param defaultValue default value
     * @return mapped value or default value is key doesn't exists
     */
    public String option(String key, String defaultValue) {
        String v = options.get(key);
        if (v != null)
            return v;
        else
            return defaultValue;
    }

    class Walker extends DirectoryWalker<String> {

        private Set<String> skipDirs;
        private ArrayList<String> result;

        public Walker(Set<String> skipDirs) {
            this.skipDirs = skipDirs;
        }

        public List<String> walk(String path) throws IOException {
            result = new ArrayList<String>(1024);
            walk(new File(path), result);
            return result;
        }

        @Override
        protected void handleFile(File file, int depth,
                Collection<String> results) throws IOException {
            String p = FilenameUtils.normalize(file.getPath(), true);

            String ext = "." + FilenameUtils.getExtension(p);
            Class<? extends Builder<?>> builderClass = extToBuilder.get(ext);
            if (builderClass != null)
                results.add(p);
        }

        @Override
        protected boolean handleDirectory(File directory, int depth,
                Collection<String> results) throws IOException {
            String path = FilenameUtils.normalize(directory.getPath());
            for (String sd : skipDirs) {
                if (path.endsWith(sd)) {
                    return false;
                }
            }
            return super.handleDirectory(directory, depth, results);
        }

        public List<String> getResult() {
            return result;
        }
    }

    /**
     * Scan for input files
     * @param path path to begin scanning in
     * @param skipDirs
     * @throws IOException
     */
    public void scan(final String path, Set<String> skipDirs) throws IOException {

        Walker walker = new Walker(skipDirs);
        walker.walk(path);
        List<String> result = walker.getResult();
        inputs = result;
    }

    public IResource getResource(String path) {
        return fileSystem.get(path);
    }

}
