package com.mirantis.mk

/**
 *
 * Tests providing functions
 *
 */

/**
 * Run e2e conformance tests
 *
 * @param target        Kubernetes node to run tests from
 * @param k8s_api       Kubernetes api address
 * @param image         Docker image with tests
 * @param timeout       Timeout waiting for e2e conformance tests
 */
def runConformanceTests(master, target, k8s_api, image, timeout=2400) {
    def salt = new com.mirantis.mk.Salt()
    def containerName = 'conformance_tests'
    def outfile = "/tmp/" + image.replaceAll('/', '-') + '.output'
    salt.cmdRun(master, target, "docker rm -f ${containerName}", false)
    salt.cmdRun(master, target, "docker run -d --name ${containerName} --net=host -e API_SERVER=${k8s_api} ${image}")
    sleep(10)

    print("Waiting for tests to run...")
    salt.runSaltProcessStep(master, target, 'cmd.run', ["docker wait ${containerName}"], null, false, timeout)

    print("Writing test results to output file...")
    salt.runSaltProcessStep(master, target, 'cmd.run', ["docker logs -t ${containerName} > ${outfile}"])
    print("Conformance test output saved in " + outfile)
}


/**
 * Upload conformance results to cfg node
 *
 * @param target        Kubernetes node for copy test results
 * @param artifacts_dir Path with test results
 */
def CopyConformanceResults(master, target, artifacts_dir, output_file) {
    def salt = new com.mirantis.mk.Salt()
    def containerName = 'conformance_tests'
    def test_node = target.replace("*", "")

    out = salt.runSaltProcessStep(master, target, 'cmd.run', ["docker cp ${containerName}:/report /tmp"])
    if (! out['return'][0].values()[0].contains('Error')) {
        print("Copy XML test results for junit artifacts...")
        salt.runSaltProcessStep(master, target, 'cmd.run', ["tar -cf /tmp/${output_file} -C /tmp/report  ."])

        writeFile file: "${artifacts_dir}${output_file}", text: salt.getFileContent(master,
                              target, "/tmp/${output_file}")

        sh "mkdir -p ${artifacts_dir}/conformance_tests"
        sh "tar -xf ${artifacts_dir}${output_file} -C ${artifacts_dir}/conformance_tests"

        // collect artifacts
        archiveArtifacts artifacts: "${artifacts_dir}${output_file}"

        junit(keepLongStdio: true, testResults:  "${artifacts_dir}conformance_tests/**.xml")
    }
}

/**
 * Copy test output to cfg node
 *
 * @param image      Docker image with tests
 */
def copyTestsOutput(master, image) {
    def salt = new com.mirantis.mk.Salt()
    salt.runSaltProcessStep(master, 'cfg01*', 'cmd.run', ["scp ctl01:/root/${image}.output /home/ubuntu/"])
}

/**
 * Execute tempest tests
 *
 * @param dockerImageLink   Docker image link with rally and tempest
 * @param target            Host to run tests
 * @param pattern           If not false, will run only tests matched the pattern
 * @param logDir            Directory to store tempest/rally reports
 * @param sourceFile        Path to the keystonerc file in the container
 * @param set               Predefined set for tempest tests
 * @param concurrency       How many processes to use to run Tempest tests
 * @param tempestConf       A tempest.conf's file name
 * @param skipList          A skip.list's file name
 * @param localKeystone     Path to the keystonerc file in the local host
 * @param localLogDir       Path to local destination folder for logs
 */
def runTempestTests(master, dockerImageLink, target, pattern = "", logDir = "/home/rally/rally_reports/",
                    sourceFile="/home/rally/keystonercv3", set="full", concurrency="0", tempestConf="mcp.conf",
                    skipList="mcp_skip.list", localKeystone="/root/keystonercv3" , localLogDir="/root/rally_reports",
                    doCleanupResources = "false") {
    def salt = new com.mirantis.mk.Salt()
    salt.runSaltProcessStep(master, target, 'file.mkdir', ["${localLogDir}"])
    def custom = ''
    if (pattern) {
        custom = "--pattern " + pattern
    }
    salt.cmdRun(master, "${target}", "docker run --rm --net=host " +
                                    "-e SOURCE_FILE=${sourceFile} " +
                                    "-e LOG_DIR=${logDir} " +
                                    "-e SET=${set} " +
                                    "-e CUSTOM='${custom}' " +
                                    "-e CONCURRENCY=${concurrency} " +
                                    "-e TEMPEST_CONF=${tempestConf} " +
                                    "-e SKIP_LIST=${skipList} " +
                                    "-e DO_CLEANUP_RESOURCES=${doCleanupResources} " +
                                    "-v ${localKeystone}:${sourceFile} " +
                                    "-v ${localLogDir}:/home/rally/rally_reports " +
                                    "-v /etc/ssl/certs/:/etc/ssl/certs/ " +
                                    "${dockerImageLink} >> docker-tempest.log")
}


/**
 * Execute Rally scenarios
 *
 * @param dockerImageLink      Docker image link with rally and tempest
 * @param target               Host to run scenarios
 * @param scenario             Specify the scenario as a string
 * @param containerName        Docker container name
 * @param doCleanupResources   Do run clean-up script after tests? Cleans up OpenStack test resources
 */
def runRallyScenarios(master, dockerImageLink, target, scenario, logDir = "/home/rally/rally_reports/",
                      doCleanupResources = "false", containerName = "rally_ci") {
    def salt = new com.mirantis.mk.Salt()
    salt.runSaltProcessStep(master, target, 'file.mkdir', ["/root/rally_reports"])
    salt.cmdRun(master, target, "docker run --net=host -dit " +
                                "--name ${containerName} " +
                                "-e SOURCE_FILE=keystonercv3 " +
                                "-e SCENARIO=${scenario} " +
                                "-e DO_CLEANUP_RESOURCES=${doCleanupResources} " +
                                "-e LOG_DIR=${logDir} " +
                                "--entrypoint /bin/bash -v /root/:/home/rally ${dockerImageLink}")
    salt.cmdRun(master, target, "docker exec ${containerName} " +
                                "bash -c /usr/bin/run-rally | tee -a docker-rally.log")
}


/**
 * Upload results to cfg01 node
 *
 */
def copyTempestResults(master, target) {
    def salt = new com.mirantis.mk.Salt()
    if (! target.contains('cfg')) {
        salt.cmdRun(master, target, "mkdir -p /root/rally_reports/ && rsync -av /root/rally_reports/ cfg01:/root/rally_reports/")
    }
}


/** Store tests results on host
 *
 * @param image      Docker image name
 */
def catTestsOutput(master, image) {
    def salt = new com.mirantis.mk.Salt()
    salt.cmdRun(master, 'cfg01*', "cat /home/ubuntu/${image}.output")
}


/** Install docker if needed
 *
 * @param target              Target node to install docker pkg
 */
def install_docker(master, target) {
    def salt = new com.mirantis.mk.Salt()
    salt.runSaltProcessStep(master, target, 'pkg.install', ["docker.io"])
}


/** Upload Tempest test results to Testrail
 *
 * @param report              Source report to upload
 * @param image               Testrail reporter image
 * @param testGroup           Testrail test group
 * @param credentialsId       Testrail credentials id
 * @param plan                Testrail test plan
 * @param milestone           Testrail test milestone
 * @param suite               Testrail test suite
 * @param type                Use local shell or remote salt connection
 * @param master              Salt connection.
 * @param target              Target node to install docker pkg
 */

def uploadResultsTestrail(report, image, testGroup, credentialsId, plan, milestone, suite, master = null, target = 'cfg01*') {
    def salt = new com.mirantis.mk.Salt()
    def common = new com.mirantis.mk.Common()
    creds = common.getPasswordCredentials(credentialsId)
    command =  "docker run --rm --net=host " +
                           "-v ${report}:/srv/report.xml " +
                           "-e TESTRAIL_USER=${creds.username} " +
                           "-e PASS=${creds.password.toString()} " +
                           "-e TESTRAIL_PLAN_NAME=${plan} " +
                           "-e TESTRAIL_MILESTONE=${milestone} " +
                           "-e TESTRAIL_SUITE=${suite} " +
                           "-e TEST_GROUP=${testGroup} " +
                           "${image}"
    if (master == null) {
      sh(command)
    } else {
      salt.cmdRun(master, target, command)
    }
}

/** Archive Rally results in Artifacts
 *
 * @param master              Salt connection.
 * @param target              Target node to install docker pkg
 * @param reports_dir         Source directory to archive
 */

def archiveRallyArtifacts(master, target, reports_dir='/root/rally_reports') {
    def salt = new com.mirantis.mk.Salt()

    def artifacts_dir = '_artifacts/'
    def output_file = 'rally_reports.tar'

    salt.cmdRun(master, target, "tar -cf /root/${output_file} -C ${reports_dir} .")
    sh "mkdir -p ${artifacts_dir}"

    encoded = salt.cmdRun(master, target, "cat /root/${output_file}", true, null, false)['return'][0].values()[0].replaceAll('Salt command execution success','')

    writeFile file: "${artifacts_dir}${output_file}", text: encoded

    // collect artifacts
    archiveArtifacts artifacts: "${artifacts_dir}${output_file}"
}
/**
 * Helper function for collecting junit tests results
 * @param testResultAction - test result from build - use: currentBuild.rawBuild.getAction(AbstractTestResultAction.class)
 * @return resultMap with structure ["total": total, "passed": passed, "skipped": skipped, "failed": failed]
 */
@NonCPS
def collectJUnitResults(testResultAction) {
    if (testResultAction != null) {
       def total = testResultAction.totalCount
       def failed = testResultAction.failCount
       def skipped = testResultAction.skipCount
       def passed = total - failed - skipped
       return ["total": total, "passed": passed, "skipped": skipped, "failed": failed]
    }else{
        def common = new com.mirantis.mk.Common()
        common.errorMsg("Cannot collect jUnit tests results, given result is null")
    }
    return [:]
}


/** Cleanup: Remove reports directory
 *
 * @param target                   Target node to remove repo
 * @param reports_dir_name         Reports directory name to be removed (that is in /root/ on target node)
 * @param archive_artifacts_name   Archive of the artifacts
 */
def removeReports(master, target, reports_dir_name = 'rally_reports', archive_artifacts_name = 'rally_reports.tar') {
    def salt = new com.mirantis.mk.Salt()
    salt.runSaltProcessStep(master, target, 'file.find', ["/root/${reports_dir_name}", '\\*', 'delete'])
    salt.runSaltProcessStep(master, target, 'file.remove', ["/root/${archive_artifacts_name}"])
}


/** Cleanup: Remove Docker container
 *
 * @param target              Target node to remove Docker container
 * @param image_link          The link of the Docker image that was used for the container
 */
def removeDockerContainer(master, target, containerName) {
    def salt = new com.mirantis.mk.Salt()
    salt.cmdRun(master, target, "docker rm -f ${containerName}")
}
