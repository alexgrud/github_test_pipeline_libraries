package com.mirantis.mk

/**
 * Setup Docker to run some tests. Returns true/false based on
 were tests successful or not.
 * @param config - LinkedHashMap with configuration params:
 *   dockerHostname - (required) Hostname to use for Docker container.
 *   formulasRevision - (optional) Revision of packages to use (default stable).
 *   runCommands - (optional) Dict with closure structure of body required tests. For example:
 *     [ '001_Test': { sh("./run-some-test") }, '002_Test': { sh("./run-another-test") } ]
 *     Before execution runCommands will be sorted by key names. Alpabetical order is preferred.
 *   runFinally - (optional) Dict with closure structure of body required commands, which should be
 *     executed in any case of test results. Same format as for runCommands
 *   updateRepo - (optional) Whether to run common repo update step.
 *   dockerContainerName - (optional) Docker container name.
 *   dockerImageName - (optional) Docker image name
 *   dockerMaxCpus - (optional) Number of CPUS to use in Docker.
 *   dockerExtraOpts - (optional) Array of Docker extra opts for container
 *   envOpts - (optional) Array of variables that should be passed as ENV vars to Docker container.
 * Return true | false
 */

def setupDockerAndTest(LinkedHashMap config) {
    def common = new com.mirantis.mk.Common()
    def TestMarkerResult = false
    // setup options
    def defaultContainerName = 'test-' + UUID.randomUUID().toString()
    def dockerHostname = config.get('dockerHostname', defaultContainerName)
    def formulasRevision = config.get('formulasRevision', 'proposed')
    def runCommands = config.get('runCommands', [:])
    def runFinally = config.get('runFinally', [:])
    def baseRepoPreConfig = config.get('baseRepoPreConfig', true)
    def dockerContainerName = config.get('dockerContainerName', defaultContainerName)
    def dockerImageName = config.get('image', "mirantis/salt:saltstack-ubuntu-xenial-salt-2017.7")
    def dockerMaxCpus = config.get('dockerMaxCpus', 4)
    def dockerExtraOpts = config.get('dockerExtraOpts', [])
    def envOpts = config.get('envOpts', [])
    envOpts.add("DISTRIB_REVISION=${formulasRevision}")
    def dockerBaseOpts = [
        '-u root:root',
        "--hostname=${dockerHostname}",
        '--ulimit nofile=4096:8192',
        "--name=${dockerContainerName}",
        "--cpus=${dockerMaxCpus}"
    ]

    def dockerOptsFinal = (dockerBaseOpts + dockerExtraOpts).join(' ')
    def img = docker.image(dockerImageName)
    img.pull()

    try {
        img.inside(dockerOptsFinal) {
            withEnv(envOpts) {
                try {
                    // Currently, we don't have any other point to install
                    // runtime dependencies for tests.
                    if (baseRepoPreConfig) {
                        sh("""#!/bin/bash -xe
                            echo "Installing extra-deb dependencies inside docker:"
                            echo "APT::Get::AllowUnauthenticated 'true';"  > /etc/apt/apt.conf.d/99setupAndTestNode
                            echo "APT::Get::Install-Suggests 'false';"  >> /etc/apt/apt.conf.d/99setupAndTestNode
                            echo "APT::Get::Install-Recommends 'false';"  >> /etc/apt/apt.conf.d/99setupAndTestNode
                            rm -vf /etc/apt/sources.list.d/* || true
                            echo 'deb [arch=amd64] http://mirror.mirantis.com/$DISTRIB_REVISION/ubuntu xenial main restricted universe' > /etc/apt/sources.list
                            echo 'deb [arch=amd64] http://mirror.mirantis.com/$DISTRIB_REVISION/ubuntu xenial-updates main restricted universe' >> /etc/apt/sources.list
                            apt-get update
                            apt-get install -y python-netaddr
                        """)
                    }
                    runCommands.sort().each { command, body ->
                        common.warningMsg("Running command: ${command}")
                        // doCall is the closure implementation in groovy, allow to pass arguments to closure
                        body.call()
                    }
                    // If we didn't dropped for now - test has been passed.
                    TestMarkerResult = true
                }
                finally {
                    runFinally.sort().each { command, body ->
                        common.warningMsg("Running ${command} command.")
                        // doCall is the closure implementation in groovy, allow to pass arguments to closure
                        body.call()
                    }
                }
            }
        }
    }
    catch (Exception er) {
        common.warningMsg("IgnoreMe:Something wrong with img.Message:\n" + er.toString())
    }

    try {
        common.warningMsg("IgnoreMe:Force cleanup slave.Ignore docker-daemon errors")
        timeout(time: 10, unit: 'SECONDS') {
            sh(script: "set -x; docker kill ${dockerContainerName} || true", returnStdout: true)
        }
        timeout(time: 10, unit: 'SECONDS') {
            sh(script: "set -x; docker rm --force ${dockerContainerName} || true", returnStdout: true)
        }
    }
    catch (Exception er) {
        common.warningMsg("IgnoreMe:Timeout to delete test docker container with force!Message:\n" + er.toString())
    }

    if (TestMarkerResult) {
        common.infoMsg("Test finished: SUCCESS")
    } else {
        common.warningMsg("Test finished: FAILURE")
    }
    return TestMarkerResult
}

/**
 * Wrapper over setupDockerAndTest, to test CC model.
 *
 * @param config - dict with params:
 *   dockerHostname - (required) salt master's name
 *   clusterName - (optional) model cluster name
 *   extraFormulas - (optional) extraFormulas to install. DEPRECATED
 *   formulasSource - (optional) formulas source (git or pkg, default pkg)
 *   reclassVersion - (optional) Version of used reclass (branch, tag, ...) (optional, default master)
 *   reclassEnv - (require) directory of model
 *   ignoreClassNotfound - (optional) Ignore missing classes for reclass model (default false)
 *   aptRepoUrl - (optional) package repository with salt formulas
 *   aptRepoGPG - (optional) GPG key for apt repository with formulas
 *   testContext - (optional) Description of test
  Return: true\exception
 */

def testNode(LinkedHashMap config) {
    def common = new com.mirantis.mk.Common()
    def result = ''
    def dockerHostname = config.get('dockerHostname')
    def reclassEnv = config.get('reclassEnv')
    def clusterName = config.get('clusterName', "")
    def formulasSource = config.get('formulasSource', 'pkg')
    def extraFormulas = config.get('extraFormulas', 'linux')
    def reclassVersion = config.get('reclassVersion', 'master')
    def ignoreClassNotfound = config.get('ignoreClassNotfound', false)
    def aptRepoUrl = config.get('aptRepoUrl', "")
    def aptRepoGPG = config.get('aptRepoGPG', "")
    def testContext = config.get('testContext', 'test')
    config['envOpts'] = [
        "RECLASS_ENV=${reclassEnv}", "SALT_STOPSTART_WAIT=5",
        "MASTER_HOSTNAME=${dockerHostname}", "CLUSTER_NAME=${clusterName}",
        "MINION_ID=${dockerHostname}", "FORMULAS_SOURCE=${formulasSource}",
        "EXTRA_FORMULAS=${extraFormulas}", "RECLASS_VERSION=${reclassVersion}",
        "RECLASS_IGNORE_CLASS_NOTFOUND=${ignoreClassNotfound}", "DEBUG=1",
        "APT_REPOSITORY=${aptRepoUrl}", "APT_REPOSITORY_GPG=${aptRepoGPG}",
        "EXTRA_FORMULAS_PKG_ALL=true"
    ]

    config['runCommands'] = [
        '001_Clone_salt_formulas_scripts': {
          sh(script: 'git clone https://github.com/salt-formulas/salt-formulas-scripts /srv/salt/scripts', returnStdout: true)
        },

        '002_Prepare_something': {
            sh('''rsync -ah ${RECLASS_ENV}/* /srv/salt/reclass && echo '127.0.1.2  salt' >> /etc/hosts
              cd /srv/salt && find . -type f \\( -name '*.yml' -or -name '*.sh' \\) -exec sed -i 's/apt-mk.mirantis.com/apt.mirantis.net:8085/g' {} \\;
              cd /srv/salt && find . -type f \\( -name '*.yml' -or -name '*.sh' \\) -exec sed -i 's/apt.mirantis.com/apt.mirantis.net:8085/g' {} \\;
            ''')
        },

        // should be switched on packages later
        '003_Install_reclass': {
            sh('''for s in \$(python -c \"import site; print(' '.join(site.getsitepackages()))\"); do
              sudo -H pip install --install-option=\"--prefix=\" --upgrade --force-reinstall -I \
              -t \"\$s\" git+https://github.com/salt-formulas/reclass.git@${RECLASS_VERSION};
              done
            ''')
        },

        '004_Run_tests': {
            def testTimeout = 40 * 60
            timeout(time: testTimeout, unit: 'SECONDS') {
              sh('''#!/bin/bash
                source /srv/salt/scripts/bootstrap.sh
                cd /srv/salt/scripts
                source_local_envs
                configure_salt_master
                configure_salt_minion
                install_salt_formula_pkg
                source /srv/salt/scripts/bootstrap.sh
                cd /srv/salt/scripts
                saltservice_restart''')

              sh('''#!/bin/bash
                source /srv/salt/scripts/bootstrap.sh
                cd /srv/salt/scripts
                source_local_envs
                saltmaster_init''')

              sh('''#!/bin/bash
                source /srv/salt/scripts/bootstrap.sh
                cd /srv/salt/scripts
                verify_salt_minions''')
            }
        }
    ]
    config['runFinally'] = [
        '001_Archive_artefacts': {
            sh(script: "cd /tmp; tar -czf ${env.WORKSPACE}/nodesinfo.tar.gz *reclass*", returnStatus: true)
            archiveArtifacts artifacts: "nodesinfo.tar.gz"
        }
    ]
    testResult = setupDockerAndTest(config)
    if (testResult) {
        common.infoMsg("Node test for context: ${testContext} model: ${reclassEnv} finished: SUCCESS")
    } else {
        throw new RuntimeException("Node test for context: ${testContext} model: ${reclassEnv} finished: FAILURE")
    }
    return testResult
}

/**
 * setup and test salt-master
 *
 * @param masterName salt master's name
 * @param clusterName model cluster name
 * @param extraFormulas extraFormulas to install. DEPRECATED
 * @param formulasSource formulas source (git or pkg)
 * @param reclassVersion Version of used reclass (branch, tag, ...) (optional, default master)
 * @param testDir directory of model
 * @param formulasSource Salt formulas source type (optional, default pkg)
 * @param formulasRevision APT revision for formulas (optional default stable)
 * @param ignoreClassNotfound Ignore missing classes for reclass model
 * @param dockerMaxCpus max cpus passed to docker (default 0, disabled)
 * @param legacyTestingMode do you want to enable legacy testing mode (iterating through the nodes directory definitions instead of reading cluster models)
 * @param aptRepoUrl package repository with salt formulas
 * @param aptRepoGPG GPG key for apt repository with formulas
 * Return                     true | false
 */

def setupAndTestNode(masterName, clusterName, extraFormulas = '*', testDir, formulasSource = 'pkg',
                     formulasRevision = 'stable', reclassVersion = "master", dockerMaxCpus = 0,
                     ignoreClassNotfound = false, legacyTestingMode = false, aptRepoUrl = '', aptRepoGPG = '', dockerContainerName = false) {
    def common = new com.mirantis.mk.Common()
    // TODO
    common.errorMsg('You are using deprecated function!Please migrate to "setupDockerAndTest".' +
        'It would be removed after 2018.q4 release!Pushing forced 60s sleep..')
    sh('sleep 60')
    // timeout for test execution (40min)
    def testTimeout = 40 * 60
    def TestMarkerResult = false
    def saltOpts = "--retcode-passthrough --force-color"
    def workspace = common.getWorkspace()
    def img = docker.image("mirantis/salt:saltstack-ubuntu-xenial-salt-2017.7")
    img.pull()

    if (formulasSource == 'pkg') {
        if (extraFormulas) {
            common.warningMsg("You have passed deprecated variable:extraFormulas=${extraFormulas}. " +
                "\n It would be ignored, and all formulas would be installed anyway")
        }
    }
    if (!dockerContainerName) {
        dockerContainerName = 'setupAndTestNode' + UUID.randomUUID().toString()
    }
    def dockerMaxCpusOpt = "--cpus=4"
    if (dockerMaxCpus > 0) {
        dockerMaxCpusOpt = "--cpus=${dockerMaxCpus}"
    }
    try {
        img.inside("-u root:root --hostname=${masterName} --ulimit nofile=4096:8192 ${dockerMaxCpusOpt} --name=${dockerContainerName}") {
            withEnv(["FORMULAS_SOURCE=${formulasSource}", "EXTRA_FORMULAS=${extraFormulas}", "EXTRA_FORMULAS_PKG_ALL=true",
                     "DISTRIB_REVISION=${formulasRevision}",
                     "DEBUG=1", "MASTER_HOSTNAME=${masterName}",
                     "CLUSTER_NAME=${clusterName}", "MINION_ID=${masterName}",
                     "RECLASS_VERSION=${reclassVersion}", "RECLASS_IGNORE_CLASS_NOTFOUND=${ignoreClassNotfound}",
                     "APT_REPOSITORY=${aptRepoUrl}", "SALT_STOPSTART_WAIT=5",
                     "APT_REPOSITORY_GPG=${aptRepoGPG}"]) {
                try {
                    // Currently, we don't have any other point to install
                    // runtime dependencies for tests.
                    sh("""#!/bin/bash -xe
            echo "Installing extra-deb dependencies inside docker:"
            echo "APT::Get::AllowUnauthenticated 'true';"  > /etc/apt/apt.conf.d/99setupAndTestNode
            echo "APT::Get::Install-Suggests 'false';"  >> /etc/apt/apt.conf.d/99setupAndTestNode
            echo "APT::Get::Install-Recommends 'false';"  >> /etc/apt/apt.conf.d/99setupAndTestNode
            rm -vf /etc/apt/sources.list.d/* || true
            echo 'deb [arch=amd64] http://mirror.mirantis.com/$DISTRIB_REVISION/ubuntu xenial main restricted universe' > /etc/apt/sources.list
            echo 'deb [arch=amd64] http://mirror.mirantis.com/$DISTRIB_REVISION/ubuntu xenial-updates main restricted universe' >> /etc/apt/sources.list
            apt-get update
            apt-get install -y python-netaddr
            """)
                    sh(script: "git clone https://github.com/salt-formulas/salt-formulas-scripts /srv/salt/scripts", returnStdout: true)
                    sh("""rsync -ah ${testDir}/* /srv/salt/reclass && echo '127.0.1.2  salt' >> /etc/hosts
            cd /srv/salt && find . -type f \\( -name '*.yml' -or -name '*.sh' \\) -exec sed -i 's/apt-mk.mirantis.com/apt.mirantis.net:8085/g' {} \\;
            cd /srv/salt && find . -type f \\( -name '*.yml' -or -name '*.sh' \\) -exec sed -i 's/apt.mirantis.com/apt.mirantis.net:8085/g' {} \\;
            """)
                    // FIXME: should be changed to use reclass from mcp_extra_nigtly?
                    sh("""for s in \$(python -c \"import site; print(' '.join(site.getsitepackages()))\"); do
            sudo -H pip install --install-option=\"--prefix=\" --upgrade --force-reinstall -I \
            -t \"\$s\" git+https://github.com/salt-formulas/reclass.git@${reclassVersion};
            done""")
                    timeout(time: testTimeout, unit: 'SECONDS') {
                        sh('''#!/bin/bash
              source /srv/salt/scripts/bootstrap.sh
              cd /srv/salt/scripts
              source_local_envs
              configure_salt_master
              configure_salt_minion
              install_salt_formula_pkg
              source /srv/salt/scripts/bootstrap.sh
              cd /srv/salt/scripts
              saltservice_restart''')
                        sh('''#!/bin/bash
              source /srv/salt/scripts/bootstrap.sh
              cd /srv/salt/scripts
              source_local_envs
              saltmaster_init''')

                        if (!legacyTestingMode.toBoolean()) {
                            sh('''#!/bin/bash
                source /srv/salt/scripts/bootstrap.sh
                cd /srv/salt/scripts
                verify_salt_minions
                ''')
                        }
                    }
                    // If we didn't dropped for now - test has been passed.
                    TestMarkerResult = true
                }

                finally {
                    // Collect rendered per-node data.Those info could be simply used
                    // for diff processing. Data was generated via reclass.cli --nodeinfo,
                    /// during verify_salt_minions.
                    sh(script: "cd /tmp; tar -czf ${env.WORKSPACE}/nodesinfo.tar.gz *reclass*", returnStatus: true)
                    archiveArtifacts artifacts: "nodesinfo.tar.gz"
                }
            }
        }
    }
    catch (Exception er) {
        common.warningMsg("IgnoreMe:Something wrong with img.Message:\n" + er.toString())
    }

    if (legacyTestingMode.toBoolean()) {
        common.infoMsg("Running legacy mode test for master hostname ${masterName}")
        def nodes = sh(script: "find /srv/salt/reclass/nodes -name '*.yml' | grep -v 'cfg*.yml'", returnStdout: true)
        for (minion in nodes.tokenize()) {
            def basename = sh(script: "set +x;basename ${minion} .yml", returnStdout: true)
            if (!basename.trim().contains(masterName)) {
                testMinion(basename.trim())
            }
        }
    }

    try {
        common.warningMsg("IgnoreMe:Force cleanup slave.Ignore docker-daemon errors")
        timeout(time: 10, unit: 'SECONDS') {
            sh(script: "set -x; docker kill ${dockerContainerName} || true", returnStdout: true)
        }
        timeout(time: 10, unit: 'SECONDS') {
            sh(script: "set -x; docker rm --force ${dockerContainerName} || true", returnStdout: true)
        }
    }
    catch (Exception er) {
        common.warningMsg("IgnoreMe:Timeout to delete test docker container with force!Message:\n" + er.toString())
    }

    if (TestMarkerResult) {
        common.infoMsg("Test finished: SUCCESS")
    } else {
        common.warningMsg("Test finished: FAILURE")
    }
    return TestMarkerResult

}

/**
 * Test salt-minion
 *
 * @param minion salt minion
 */

def testMinion(minionName) {
    sh(script: "bash -c 'source /srv/salt/scripts/bootstrap.sh; cd /srv/salt/scripts && verify_salt_minion ${minionName}'", returnStdout: true)
}

/**
 * Wrapper over setupAndTestNode, to test exactly one CC model.
 Whole workspace and model - should be pre-rendered and passed via MODELS_TARGZ
 Flow: grab all data, and pass to setupAndTestNode function
 under-modell will be directly mirrored to `model/{cfg.testReclassEnv}/* /srv/salt/reclass/*`
 *
 * @param cfg - dict with params:
 MODELS_TARGZ       http link to arch with (models|contexts|global_reclass)
 modelFile
 DockerCName        directly passed to setupAndTestNode
 EXTRA_FORMULAS     directly passed to setupAndTestNode
 DISTRIB_REVISION   directly passed to setupAndTestNode
 reclassVersion     directly passed to setupAndTestNode

 Return: true\exception
 */

def testCCModel(cfg) {
    def common = new com.mirantis.mk.Common()
    common.errorMsg('You are using deprecated function!Please migrate to "testNode".' +
        'It would be removed after 2018.q4 release!Pushing forced 60s sleep..')
    sh('sleep 60')
    sh(script: 'find . -mindepth 1 -delete || true', returnStatus: true)
    sh(script: "wget --progress=dot:mega --auth-no-challenge -O models.tar.gz ${cfg.MODELS_TARGZ}")
    // unpack data
    sh(script: "tar -xzf models.tar.gz ")
    common.infoMsg("Going to test exactly one context: ${cfg.modelFile}\n, with params: ${cfg}")
    content = readFile(file: cfg.modelFile)
    templateContext = readYaml text: content
    clusterName = templateContext.default_context.cluster_name
    clusterDomain = templateContext.default_context.cluster_domain

    def testResult = false
    testResult = setupAndTestNode(
        "cfg01.${clusterDomain}",
        clusterName,
        '',
        cfg.testReclassEnv, // Sync into image exactly one env
        'pkg',
        cfg.DISTRIB_REVISION,
        cfg.reclassVersion,
        0,
        false,
        false,
        '',
        '',
        cfg.DockerCName)
    if (testResult) {
        common.infoMsg("testCCModel for context: ${cfg.modelFile} model: ${cfg.testReclassEnv} finished: SUCCESS")
    } else {
        throw new RuntimeException("testCCModel for context: ${cfg.modelFile} model: ${cfg.testReclassEnv} finished: FAILURE")
    }
    return testResult
}
