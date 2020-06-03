
buildMvn {
  publishModDescriptor = false
  publishAPI = false
  mvnDeploy = true
  runLintRamlCop = false
  doKubeDeploy = false
  buildNode = true


  doDocker = {
    buildJavaDocker {
      publishMaster = tue
      healthChk = true
      healthChkCmd = 'curl -sS --fail -o /dev/null  http://localhost:8081/apidocs/ || exit 1'
    }
  }
}

