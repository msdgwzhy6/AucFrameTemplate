import org.apache.commons.io.FileUtils
import org.gradle.BuildListener
import org.gradle.BuildResult
import org.gradle.api.Project
import org.gradle.api.ProjectEvaluationListener
import org.gradle.api.ProjectState
import org.gradle.api.Task
import org.gradle.api.execution.TaskExecutionListener
import org.gradle.api.initialization.Settings
import org.gradle.api.invocation.Gradle
import org.gradle.api.tasks.TaskState

import java.text.SimpleDateFormat

class ConfigUtils {

    static getApplyPlugins() {
        def plugins = getDepConfigByFilter(new DepConfigFilter() {
            @Override
            boolean accept(String name, DepConfig config) {
                if (!name.startsWith("plugin.")) return false
                if (!config.isApply) return false
                return true
            }
        })
        GLog.d("getApplyPlugins = ${GLog.object2String(plugins)}")
        return plugins
    }

    static getApplyPkgs() {
        def applyPkgs = getDepConfigByFilter(new DepConfigFilter() {
            @Override
            boolean accept(String name, DepConfig config) {
                if (!config.isApply) return false
                return name.endsWith(".pkg")
            }
        })
        GLog.d("getApplyPkgs = ${GLog.object2String(applyPkgs)}")
        return applyPkgs
    }

    static getApplyExports() {
        def applyExports = getDepConfigByFilter(new DepConfigFilter() {
            @Override
            boolean accept(String name, DepConfig config) {
                if (!config.isApply) return false
                return name.endsWith(".export")
            }
        })
        GLog.d("getApplyExports = ${GLog.object2String(applyExports)}")
        return applyExports
    }

    static addBuildListener(Gradle g) {
        g.addBuildListener(new ConfigBuildListener())
    }

    private static class ConfigBuildListener implements BuildListener {

        private List<TaskInfo> taskInfoList = []
        private long startBuildMillis

        @Override
        void buildStarted(Gradle gradle) {}

        @Override
        void settingsEvaluated(Settings settings) {
            startBuildMillis = System.currentTimeMillis()
            GLog.d("settingsEvaluated")
            includeModule(settings)
        }

        @Override
        void projectsLoaded(Gradle gradle) {
            GLog.d("projectsLoaded")
            generateDep(gradle)

            gradle.addProjectEvaluationListener(new ProjectEvaluationListener() {
                @Override
                void beforeEvaluate(Project project) {
                    if (project.subprojects.isEmpty()) {// 定位到具体 project
                        if (project.name == 'dep') return
                        if (project.name == "app") {
                            GLog.l(project.toString() + " applies buildApp.gradle")
                            project.apply {
                                from "${project.rootDir.path}/buildApp.gradle"
                            }
                        } else {
                            GLog.l(project.toString() + " applies buildLib.gradle")
                            project.apply {
                                from "${project.rootDir.path}/buildLib.gradle"
                            }
                        }
                    }
                }

                @Override
                void afterEvaluate(Project project, ProjectState projectState) {
                }
            })
        }

        @Override
        void projectsEvaluated(Gradle gradle) {
            GLog.d("projectsEvaluated")
            gradle.addListener(new TaskExecutionListener() {
                @Override
                void beforeExecute(Task task) {
                    task.ext.startTime = System.currentTimeMillis()
                }

                @Override
                void afterExecute(Task task, TaskState state) {
                    def exeDuration = System.currentTimeMillis() - task.ext.startTime
                    if (exeDuration >= 100) {
                        taskInfoList.add(new TaskInfo(task, exeDuration))
                    }
                }
            })
        }

        @Override
        void buildFinished(BuildResult result) {
            GLog.d("buildFinished")
            if (!taskInfoList.isEmpty()) {
                Collections.sort(taskInfoList, new Comparator<TaskInfo>() {
                    @Override
                    int compare(TaskInfo t, TaskInfo t1) {
                        return t1.exeDuration - t.exeDuration
                    }
                })
                StringBuilder sb = new StringBuilder()
                int buildSec = (System.currentTimeMillis() - startBuildMillis) / 1000;
                int m = buildSec / 60;
                int s = buildSec % 60;
                def timeInfo = (m == 0 ? "${s}s" : "${m}m ${s}s (${buildSec}s)")
                sb.append("BUILD FINISHED in $timeInfo\n")
                taskInfoList.each {
                    sb.append(String.format("%7sms %s\n", it.exeDuration, it.task.path))
                }
                def content = sb.toString()
                GLog.l(content)
                File file = new File(result.gradle.rootProject.buildDir.getAbsolutePath(),
                        "build_time_records_" + new SimpleDateFormat("yyyy-MM-dd-HH-mm-ss").format(new Date()) + ".txt")
                FileUtils.write(file, content)
            }
        }

        /**
         * 在 settings.gradle 中 根据 appConfig 和 pkgConfig 来 include 本地模块
         */
        private static includeModule(Settings settings) {
            if (Config.pkgConfig.isEmpty()) {
                Config.depConfig.feature.mock.isApply = false
            }
            def config = getDepConfigByFilter(new DepConfigFilter() {
                @Override
                boolean accept(String name, DepConfig config) {
                    if (name.endsWith('.app')) {// 如果最终是 app 的话
                        def appName = name.substring('feature.'.length(), name.length() - 4)// 获取 app 模块的名字
                        if (!Config.appConfig.contains(appName)) {// 如果 Config.appConfig 中不存在，那就不让它进依赖
                            config.isApply = false
                        }
                    }
                    if (name.endsWith('.pkg')) {// 如果是 pkg 的话
                        if (!Config.pkgConfig.isEmpty()) {// 如果 Config.pkgConfig 不为空，说明是 pkg 调试模式
                            def pkgName = name.substring('feature.'.length(), name.length() - 4)// 获取 pkg 模块的名字
                            if (!Config.pkgConfig.contains(pkgName)) {// 如果 Config.pkgConfig 中不存在，那就不让它进依赖
                                config.isApply = false
                            }
                        }
                    }
                    // 过滤出本地并且 apply 的模块
                    if (!config.isApply) return false
                    if (!config.useLocal) return false
                    if (config.localPath == "") return false
                    return true
                }
            }).each { _, cfg ->// 把本地模块 include 进去
                settings.include cfg.localPath
            }
            GLog.l("includeModule = ${GLog.object2String(config)}")
        }

        /**
         * 根据 depConfig 生成 dep
         */
        private static generateDep(Gradle gradle) {
            def config = getDepConfigByFilter(new DepConfigFilter() {
                @Override
                boolean accept(String name, DepConfig config) {
                    if (config.useLocal) {// 如果使用的是本地模块，那么把它转化为 project
                        config.dep = gradle.rootProject.findProject(config.localPath)
                    } else {// 如果是远端依赖，那就直接使用远端依赖即可
                        config.dep = config.remotePath
                    }
                    return true
                }
            })
            GLog.l("generateDep = ${GLog.object2String(config)}")
        }

        private static class TaskInfo {

            Task task
            long exeDuration

            TaskInfo(Task task, long exeDuration) {
                this.task = task
                this.exeDuration = exeDuration
            }
        }
    }

    /**
     * 根据过滤器来获取 DepConfig
     */
    static Map<String, DepConfig> getDepConfigByFilter(DepConfigFilter filter) {
        return _getDepConfigByFilter("", Config.depConfig, filter)
    }

    private static _getDepConfigByFilter(String namePrefix, Map map, DepConfigFilter filter) {
        def depConfigList = [:]// 结果 Map
        for (Map.Entry entry : map.entrySet()) {
            def (name, value) = [entry.getKey(), entry.getValue()]
            if (value instanceof Map) {// 如果值是 Map 类型就加到结果 Map 中
                namePrefix += (name + '.')
                depConfigList.putAll(_getDepConfigByFilter(namePrefix, value, filter))
                namePrefix -= (name + '.')
                continue
            }
            def config = value as DepConfig
            if (filter == null || filter.accept(namePrefix + name, config)) {
                depConfigList.put(namePrefix + name, config)// 符合过滤条件就加到结果 Map 中
            }
        }
        return depConfigList
    }

    interface DepConfigFilter {
        boolean accept(String name, DepConfig config);
    }
}