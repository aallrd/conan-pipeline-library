@Grab(group='com.github.zafarkhaja', module='java-semver', version='0.9.0')
import com.github.zafarkhaja.semver.Version;
import groovy.json.JsonSlurperClassic

@NonCPS
def setJobDisplayStatus(toolchains) {
  currentBuild.description = ""
  def engine = new groovy.text.SimpleTemplateEngine()
  def binding = []
  def template = '<div style=\"display:flex;align-items:center;\">\n<span>${toolchain}:&nbsp;</span><img src=\"https://raw.githubusercontent.com/ovinn/modernstatus-plugin/master/src/main/webapp/16x16/${color}.png\" alt=\"${status}\" height=\"16\" width=\"16\"><br/>\n</div>'
  toolchains.each { name, toolchain ->
    binding = [
    "toolchain": "${toolchain.shortname}",
    "color": ("${toolchain.status}" == "SUCCESS") ? "blue" : "red",
    "status": "${toolchain.status}"
    ]
    status = engine.createTemplate(template).make(binding)
    currentBuild.description = currentBuild.description + status
  }
}

@NonCPS
def getConanToolchains(settings) {
  def raw_toolchains = new JsonSlurperClassic().parseText(libraryResource('com/org/conan/toolchains.json'))
  def disabled = [:]
  def enabled = [:]
  if(settings.compilers) {
    raw_toolchains.each { name, toolchain ->
      Version v = Version.valueOf("${toolchain.version}");
      try {
        require = settings.compilers["${toolchain.name}"]
        boolean check = v.satisfies(require)
        if (check) {
          println("[Configured] Toolchain enabled: ${toolchain.name} - ${toolchain.version}")
          enabled.put(name, toolchain)
        } else {
          println("[Configured] Toolchain disabled: ${toolchain.name} - ${toolchain.version}")
        }
      } catch (Exception e) {
        println("[Default] Toolchain ${toolchain.name} not referenced, skipping.")
      }
    }
    return [enabled, disabled]
  } else {
    println("[Default] No restrictions on toolchain selection")
    return [raw_toolchains, disabled]
  }
}

@NonCPS
def getConanChannel(settings) {
  if (settings.channel) {
    return settings.channel
  } else {
    return "org/testing"
  }
}

def generateToolchainMCPScript(toolchains) {
  def choices = []
  log("generateToolchainMCPScript: toolchains: " + toolchains)
  toolchains.each { name, toolchain ->
    if (toolchain.default) {
      choices << "\'${name}:selected\'"
    } else {
      choices << "\'${name}\'"
    }
  }
  choices = choices.join(',\n')
  log("generateToolchainMCPScript: choices: " + choices)
  return """return [\n${choices}\n]"""
}

// These parameters are accessible through the params object
def setPipelineJobParameters(toolchains, channel) {
  log("generateToolchainMCPScript: " + generateToolchainMCPScript(toolchains))
  return properties([
  parameters([
  [$class     : 'ChoiceParameter',
  choiceType : 'PT_CHECKBOX',
  description: 'The toolchains enabled for this Conan project',
  name       : 'toolchains',
  randomName : 'choice-parameter-10368156122636365',
  script     : [$class: 'GroovyScript', script: [classpath: [], sandbox: false, script: generateToolchainMCPScript(toolchains)]]
  ],
  string(defaultValue: "${channel}", description: 'The Conan testing publication channel', name: 'channel')
  ]),
  pipelineTriggers([cron('@midnight')])
  ])
}

def getJobParameters(params, enabled_toolchains, settings_channel) {
  def param_toolchains = [:]
  def param_channel = settings_channel
  log("params.toolchains: " + params.toolchains)
  if (params.toolchains) {
    selected = []
    params.toolchains.tokenize(",").each { item ->
      selected.add(item.tokenize(":")[0])
    }
    enabled_toolchains.each { name, toolchain ->
      if(selected.contains(name)) {
        log("Selected toolchain: ${name}")
        toolchain.put("status", "PENDING")
        param_toolchains.put(name, toolchain)
      }
    }
  } else {
    log("Building on all available toolchains.")
    param_toolchains = enabled_toolchains
  }
  assert param_toolchains.size() > 0: "The selected toolchain list is empty."
  log("params.channel: " + params.channel)
  if (params.channel) {
    param_channel = params.channel
  }
  log("Parameters: " + [param_toolchains, param_channel])
  return [param_toolchains, param_channel]
}

def shell(command) {
  log("shell: " + command)
  if (isUnix()) {
    sh command
  } else {
    powershell command
  }
}

// Dumps the current Conan project information
def getConanfileProjectInfo() {
  String info = null
  if(isUnix()) {
    // Need to call it two times to make sure that we end up with
    // the current project info on the first line on the second call
    sh('conan info . -n None')
    info = sh(returnStdout: true, script: 'conan info . -n None').trim()
  } else {
    powershell('conan info . -n None')
    info = powershell(returnStdout: true, script: 'conan info . -n None').trim()
  }
  log("Conan info: " + info.tokenize())
  return info
}

// Returns the current Conan project name string by reading the Conan project info
def getConanfileProjectName() {
  // name/version@my/channel
  String name = getConanfileProjectInfo().tokenize()[0].split("@")[0].split('/')[0]
  log("Project name: " + name)
  return name
}

// Returns the current Conan project version string by reading the Conan project info
def getConanfileProjectVersion() {
  // name/version@my/channel
  String version = getConanfileProjectInfo().tokenize()[0].split("@")[0].split('/')[1]
  log("Project version: " + version)
  return version
}

// Returns the current Conan package string by reading the Conan project info
def getConanfileProjectPackage(channel) {
  // name/version@my/channel
  String nv = [getConanfileProjectName(), '/', getConanfileProjectVersion()].join()
  String pkg = [nv, "@", channel].join()
  log("Project package: " + pkg)
  return pkg
}

class Global {
  static Object verbose = "NONE"
}

def log(message) {
  if (Global.verbose != "NONE") {
    println(message)
  }
}

def call(Closure body) {
  // The settings are the ones defined in the conan { } closure
  // The params are the ones defined as job parameters
  def user_triggered = currentBuild.rawBuild.getCause(hudson.model.Cause$UserIdCause) != null ? true : false
  def conan = [:]
  def settings = [:]
  body.resolveStrategy = Closure.DELEGATE_FIRST
  body.delegate = settings
  body()

  // Activate verbose output if requested by the user
  if (settings.verbose) {
    Global.verbose = settings.verbose
    log("Verbose mode activated.")
  }

  // Parsing the CPL configuration
  log("Parsing the CPL configuration.")
  def (enabled_toolchains, disabled_toolchains) = getConanToolchains(settings)
  def settings_channel = getConanChannel(settings)

  // Setting the job parameters, toolchains and channel selection
  log("Setting the pipeline job parameters.")
  setPipelineJobParameters(enabled_toolchains, settings_channel)

  // Parsing the job parameters we are called with
  // The settings defined in the conan closure have priority
  // If the job is not triggered manually (commit, auto-scan, ...), we stick to
  // the CPL settings and we do not parse the parameters
  def toolchains, channel
  if(user_triggered) {
    log("Job was triggered manually, parsing the input parameters.")
    (toolchains, channel) = getJobParameters(params, enabled_toolchains, settings_channel)
  } else {
    log("Job was triggered automatically, using the CPL default settings.")
    (toolchains, channel) = [enabled_toolchains, settings_channel]
  }

  toolchains.each { name, toolchain ->
    conan["${toolchain.shortname}"] = {
      node(toolchain.jenkins_node) {
        ws("${env.BUILD_NUMBER}") {
          try {
            if(settings.git) {
              log("Cloning user specified git repo: ${settings.git.url} (${settings.git.branch})")
                git branch: "${settings.git.branch}", url: "${settings.git.url}"
            } else {
              checkout scm
            }
            name = getConanfileProjectName()
            pkg = getConanfileProjectPackage(channel)
            shell "conan create . ${channel} --profile=${toolchain.profile} --build=${name} --build=outdated"
            shell "conan user -r conan-remote -p conan common"
            shell "conan upload --force -r conan-remote --all -c ${pkg}"
            toolchain.status = "SUCCESS"

          } catch (Exception e) {
            toolchain.status = "FAILURE"
            throw e
          }
        }
      }
    }
  }

  try {
    stage("Conan") {
      parallel conan
      currentBuild.result = "SUCCESS"
    }
  } catch (Exception e) {
    currentBuild.result = "FAILURE"
    log("Failure: " + e.getMessage())
  } finally {
    setJobDisplayStatus(toolchains)
  }
}

