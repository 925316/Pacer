## Build

This section provides comprehensive information regarding the build process for this project. It is intended for developers, contributors, and anyone who may be interested in understanding how the application is compiled, tested, and packaged. Please read this section carefully before attempting to build the project.

All builds for this project are performed exclusively within a continuous integration (CI) environment. No local Android SDK installation is required, and local builds are not officially supported. This approach ensures that every build is executed in a consistent, reproducible, and isolated environment, thereby reducing the risk of environment-specific issues and ensuring that all artifacts are produced under identical conditions.

The build process is fully automated through GitHub Actions. Two primary workflows are defined to handle different stages of the development lifecycle:

- **Continuous Integration Workflow**: This workflow is triggered automatically whenever changes are pushed to the `master` branch. The workflow definition can be found in `.github/workflows/ci.yml`. Its primary purpose is to compile the source code, run all unit tests, and verify that the project remains in a healthy and stable state. Contributors are encouraged to ensure that their changes pass the CI workflow before merging.

- **Release Workflow**: This workflow is triggered when a tag matching the pattern `v*` is pushed. For example, tags such as `v1.0.0` or `v2.3.1` will initiate the release process. The workflow definition is located at `.github/workflows/release.yml`. The release workflow is responsible for generating a release build, signing the APK if applicable, and attaching the resulting artifact to the corresponding GitHub release.

It is important to note that the CI and release workflows are the only supported methods for building this project. Any attempts to build locally may result in unexpected behavior and are not covered by official support. We kindly remind all contributors to review the workflow files for detailed configuration and to ensure that all necessary steps are followed correctly.

Should you have any questions or encounter any issues with the build process, please refer to the workflow files for detailed configuration, or open an issue in the repository. We appreciate your understanding and cooperation.
