/*
 * The MIT License (MIT)
 *
 * Copyright (c) 2016 hsz Jakub Chrzanowski <jakub@hsz.mobi>
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in all
 * copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
 * SOFTWARE.
 */

package mobi.hsz.idea.gitignore.util.exec;

import com.intellij.execution.process.BaseOSProcessHandler;
import com.intellij.execution.process.ProcessHandler;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.util.Key;
import com.intellij.openapi.util.text.StringUtil;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.util.containers.ContainerUtil;
import git4idea.config.GitVcsApplicationSettings;
import mobi.hsz.idea.gitignore.lang.IgnoreLanguage;
import mobi.hsz.idea.gitignore.lang.kind.GitLanguage;
import mobi.hsz.idea.gitignore.util.Icons;
import mobi.hsz.idea.gitignore.util.Utils;
import org.jetbrains.annotations.NonNls;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.jps.service.SharedThreadPool;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Future;

/**
 * Class that holds util methods for calling external executables (i.e. git/hg)
 *
 * @author Jakub Chrzanowski <jakub@hsz.mobi>
 * @since 1.4
 */
public class ExternalExec {
    /** Private constructor to prevent creating {@link Icons} instance. */
    private ExternalExec() {
    }

    /** Checks if Git plugin is enabled. */
    private static final boolean GIT_ENABLED = Utils.isGitPluginEnabled();

    /** Git command to get user's excludesfile path. */
    @NonNls
    private static final String GIT_CONFIG_EXCLUDES_FILE = "config --global core.excludesfile";

    /** Git command to list unversioned files. */
    @NonNls
    private static final String GIT_UNIGNORED_FILES = "clean -dn";

    /**
     * Returns {@link VirtualFile} instance of the Git excludes file if available.
     *
     * @return Git excludes file
     */
    @Nullable
    public static VirtualFile getGitExcludesFile() {
        return runForSingle(GitLanguage.INSTANCE, GIT_CONFIG_EXCLUDES_FILE, null, new GitExcludesOutputParser());
    }

    /**
     * Returns list of unignored files for the given directory.
     *
     * @param language to check
     * @param project  current project
     * @param file     current file
     * @return unignored files list
     */
    @NotNull
    public static List<String> getUnignoredFiles(@NotNull IgnoreLanguage language, @NotNull Project project,
                                                 @NotNull VirtualFile file) {
        if (!Utils.isInProject(file, project)) {
            return ContainerUtil.newArrayList();
        }

        ArrayList<String> result = run(language, GIT_UNIGNORED_FILES, file.getParent(), new GitUnignoredFilesOutputParser());
        return Utils.notNullize(result);
    }

    /**
     * Returns path to the {@link IgnoreLanguage} binary or null if not available.
     * Currently only  {@link GitLanguage} is supported.
     *
     * @param language current language
     * @return path to binary
     */
    @Nullable
    private static String bin(@NotNull IgnoreLanguage language) {
        if (GitLanguage.INSTANCE.equals(language) && GIT_ENABLED) {
            final String bin = GitVcsApplicationSettings.getInstance().getPathToGit();
            return StringUtil.nullize(bin);
        }
        return null;
    }

    /**
     * Runs {@link IgnoreLanguage} executable with the given command and current working directory.
     *
     * @param language     current language
     * @param command      to call
     * @param directory    current working directory
     * @param parser       {@link ExecutionOutputParser} implementation
     * @param <T>          return type
     * @return result of the call
     */
    @Nullable
    private static <T> T runForSingle(@NotNull IgnoreLanguage language, @NotNull String command,
                                      @Nullable VirtualFile directory, @NotNull final ExecutionOutputParser<T> parser) {
        return ContainerUtil.getFirstItem(run(language, command, directory, parser));
    }

    /**
     * Runs {@link IgnoreLanguage} executable with the given command and current working directory.
     *
     * @param language  current language
     * @param command   to call
     * @param directory current working directory
     * @param parser    {@link ExecutionOutputParser} implementation
     * @param <T>       return type
     * @return result of the call
     */
    @Nullable
    private static <T> ArrayList<T> run(@NotNull IgnoreLanguage language, @NotNull String command,
                                        @Nullable VirtualFile directory, @NotNull final ExecutionOutputParser<T> parser) {
        final String bin = bin(language);
        if (bin == null) {
            return null;
        }

        try {
            final String cmd = bin + " " + command;
            final File workingDirectory = directory != null ? new File(directory.getPath()) : null;
            final Process process = Runtime.getRuntime().exec(cmd, null, workingDirectory);

            ProcessHandler handler = new BaseOSProcessHandler(process, StringUtil.join(cmd, " "), null) {
                @NotNull
                @Override
                protected Future<?> executeOnPooledThread(@NotNull Runnable task) {
                    return SharedThreadPool.getInstance().executeOnPooledThread(task);
                }

                @Override
                public void notifyTextAvailable(String text, Key outputType) {
                    parser.onTextAvailable(text, outputType);
                }
            };

            handler.startNotify();
            handler.waitFor();
            parser.notifyFinished(process.exitValue());

            if (parser.isErrorsReported()) {
                return null;
            }

            return parser.getOutput();
        } catch (IOException ignored) {
        }

        return null;
    }
}
