# Publishing Guide - Android SDK

This document describes how to build, version, and publish the OursPrivacy Android SDK to Maven Central.

## Overview

- **Package Name**: `com.oursprivacy.android:oursprivacy-android`
- **Target Registry**: Maven Central (https://central.sonatype.com/)
- **Current Status**: ✅ Published on Maven Central
- **Build System**: Gradle with Android Library Plugin
- **Publishing**: Automated via Gradle with Sonatype

## Prerequisites

### Tools Required
- Android Studio or Android SDK
- Java 17+
- Gradle 8.0+
- GPG for signing
- Sonatype OSSRH account

### Authentication Setup
1. **Sonatype OSSRH Account**: Access to https://oss.sonatype.org/
2. **GPG Signing**: Set up GPG key for signing artifacts
3. **Gradle Properties**: Configure signing and upload credentials

### Required Files
Create `~/.gradle/gradle.properties` with:
```properties
# Sonatype credentials
sonatypeUsername=YOUR_SONATYPE_USERNAME
sonatypePassword=YOUR_SONATYPE_PASSWORD

# GPG signing
signing.keyId=YOUR_GPG_KEY_ID
signing.password=YOUR_GPG_PASSPHRASE
signing.secretKeyRingFile=/path/to/secring.gpg
```

## Version Management

### Current Version
Check current version in `gradle.properties`:
```properties
VERSION_NAME=8.0.3
```

### Update Version
1. Edit `gradle.properties`:
   ```properties
   VERSION_NAME=8.0.4
   ```

2. Update any version references in documentation

## Building the Package

### Local Build
```bash
# Clean previous builds
./gradlew clean

# Build library
./gradlew assembleRelease

# Run tests
./gradlew test

# Generate documentation
./gradlew androidJavadocs

# Build all artifacts (AAR, sources, javadoc)
./gradlew build androidJavadocsJar androidSourcesJar
```

### Build Outputs
The build generates:
- `build/outputs/aar/ours-privacy-android-release.aar` - Main library
- `build/libs/ours-privacy-android-X.X.X-sources.jar` - Source code
- `build/libs/ours-privacy-android-X.X.X-javadoc.jar` - Documentation

## Publishing Process

### Local/Snapshot Publishing
```bash
# Publish to local Maven repository
./gradlew install

# Verify local installation
./gradlew publishDebugPublicationToMavenLocal
```

### Release Publishing

1. **Prepare Release**:
   ```bash
   # Ensure clean working directory
   git status
   
   # Update version in gradle.properties
   vim gradle.properties
   
   # Commit version bump
   git add gradle.properties
   git commit -m "chore: bump version to 8.0.4"
   ```

2. **Build and Sign**:
   ```bash
   # Build all artifacts
   ./gradlew clean build androidJavadocsJar androidSourcesJar
   
   # Sign and publish to staging
   ./gradlew publishRelease
   ```

3. **Release on Sonatype**:
   - Go to https://oss.sonatype.org/
   - Login with credentials
   - Navigate to "Staging Repositories"
   - Find your staging repository
   - Click "Close" to validate artifacts
   - After validation, click "Release"

4. **Tag and Push**:
   ```bash
   git tag -a v8.0.4 -m "Release v8.0.4"
   git push origin main
   git push origin v8.0.4
   ```

## Gradle Configuration

### Build Configuration
The project uses several key configuration files:

#### `build.gradle` (Main)
```gradle
apply plugin: 'com.android.library'
apply plugin: 'maven-publish'
apply plugin: 'signing'

group = GROUP
version = VERSION_NAME

android {
    namespace "com.oursprivacy.android"
    compileSdk 34
    
    defaultConfig {
        minSdk 21
        targetSdk 34
        buildConfigField "String", "OURSPRIVACY_VERSION", "\"${version}\""
    }
}

dependencies {
    implementation "androidx.annotation:annotation:1.8.2"
    implementation 'androidx.core:core:1.13.1'
    // ... other dependencies
}

apply from: rootProject.file('maven.gradle')
```

#### `gradle.properties`
```properties
VERSION_NAME=8.0.3
GROUP=com.oursprivacy.android

POM_PACKAGING=aar
POM_DESCRIPTION=Official OursPrivacy tracking library for Android
POM_URL=https://github.com/oursprivacy/oursprivacy-android
POM_SCM_URL=https://github.com/oursprivacy/oursprivacy-android
POM_SCM_CONNECTION=scm:git:http://github.com/oursprivacy/oursprivacy-android
POM_SCM_DEV_CONNECTION=scm:git:git@github.com:oursprivacy/oursprivacy-android.git

POM_LICENCE_NAME=Apache License 2.0
POM_LICENCE_URL=https://www.apache.org/licenses/LICENSE-2.0
POM_LICENCE_DIST=repo

POM_DEVELOPER_ID=oursprivacy_dev
POM_DEVELOPER_NAME=OursPrivacy Developers
POM_DEVELOPER_EMAIL=

RELEASE_REPOSITORY_URL=https://oss.sonatype.org/service/local/staging/deploy/maven2/
SNAPSHOT_REPOSITORY_URL=https://oss.sonatype.org/content/repositories/snapshots/
```

### Publishing Configuration
The `maven.gradle` file configures Maven publishing:

```gradle
publishing {
    publications {
        release(MavenPublication) {
            afterEvaluate {
                from components.findByName('release')
            }
            
            groupId = GROUP
            artifactId = project.name
            version = VERSION_NAME
            
            pom {
                name.set(project.name)
                description.set(POM_DESCRIPTION)
                url.set(POM_URL)
                // ... additional POM configuration
            }
        }
    }
    
    repositories {
        maven {
            url = uri(RELEASE_REPOSITORY_URL)
            credentials {
                username = getRepositoryUsername()
                password = getRepositoryPassword()
            }
        }
    }
}

signing {
    sign publishing.publications.release
}
```

## Gradle Tasks

### Key Tasks
| Task | Purpose | Command |
|------|---------|---------|
| `build` | Build library and tests | `./gradlew build` |
| `assembleRelease` | Build release AAR | `./gradlew assembleRelease` |
| `test` | Run unit tests | `./gradlew test` |
| `androidJavadocs` | Generate documentation | `./gradlew androidJavadocs` |
| `androidJavadocsJar` | Package documentation | `./gradlew androidJavadocsJar` |
| `androidSourcesJar` | Package source code | `./gradlew androidSourcesJar` |
| `install` | Install to local Maven | `./gradlew install` |
| `publishRelease` | Publish to Maven Central | `./gradlew publishRelease` |

### Custom Tasks
```bash
# Install snapshot to local .m2 folder
./gradlew install

# Publish release to Maven Central
./gradlew publishRelease
```

## Version Guidelines

Follow semantic versioning for Android:
- **Major** (8.0.0): Breaking API changes
- **Minor** (8.1.0): New features, backwards compatible
- **Patch** (8.0.1): Bug fixes, internal improvements

## Testing

### Unit Tests
```bash
# Run all tests
./gradlew test

# Run tests with coverage
./gradlew testDebugUnitTestCoverage
```

### Integration Tests
```bash
# Run instrumented tests (requires device/emulator)
./gradlew connectedAndroidTest
```

### Manual Testing
```bash
# Install in test app
implementation 'com.oursprivacy.android:oursprivacy-android:8.0.3'

# Or test local build
implementation project(':ours-privacy-android')
```

## GPG Signing Setup

### Generate GPG Key
```bash
# Generate new key
gpg --full-generate-key

# List keys
gpg --list-secret-keys --keyid-format LONG

# Export public key to keyserver
gpg --keyserver hkp://keyserver.ubuntu.com --send-keys YOUR_KEY_ID

# Export secret key for Gradle
gpg --export-secret-keys YOUR_KEY_ID > secring.gpg
```

### Configure Gradle
Add to `~/.gradle/gradle.properties`:
```properties
signing.keyId=YOUR_KEY_ID
signing.password=YOUR_GPG_PASSPHRASE
signing.secretKeyRingFile=/path/to/secring.gpg
```

## Troubleshooting

### Common Issues

1. **Signing failures**: Check GPG configuration and passphrase
2. **Upload failures**: Verify Sonatype credentials
3. **Build failures**: Check Android SDK and Gradle versions
4. **POM validation**: Ensure all required POM elements are present

### Validation Steps
```bash
# Check build configuration
./gradlew tasks --all

# Validate artifacts
./gradlew build androidJavadocsJar androidSourcesJar
ls -la build/outputs/aar/
ls -la build/libs/

# Test signing
./gradlew signReleasePublication
```

## Security Considerations

- Store credentials securely (use environment variables or encrypted properties)
- Keep GPG private key secure
- Use separate Sonatype account for automated builds
- Regularly rotate credentials

## CI/CD Integration

### GitHub Actions Example
```yaml
name: Publish to Maven Central
on:
  push:
    tags:
      - 'v*'

jobs:
  publish:
    runs-on: ubuntu-latest
    steps:
      - uses: actions/checkout@v3
      - uses: actions/setup-java@v3
        with:
          java-version: '17'
          distribution: 'adopt'
          
      - name: Setup Gradle
        uses: gradle/gradle-build-action@v2
        
      - name: Publish to Maven Central
        env:
          SONATYPE_USERNAME: ${{ secrets.SONATYPE_USERNAME }}
          SONATYPE_PASSWORD: ${{ secrets.SONATYPE_PASSWORD }}
          SIGNING_KEY_ID: ${{ secrets.SIGNING_KEY_ID }}
          SIGNING_PASSWORD: ${{ secrets.SIGNING_PASSWORD }}
          SIGNING_SECRET_KEY_RING_FILE: ${{ secrets.SIGNING_SECRET_KEY_RING_FILE }}
        run: ./gradlew publishRelease
```

## Support

- **Documentation**: https://docs.oursprivacy.com/docs/android-sdk#/
- **Maven Central**: https://central.sonatype.com/artifact/com.oursprivacy.android/oursprivacy-android
- **Issues**: https://github.com/with-ours/ours-privacy-android/issues
- **Repository**: https://github.com/with-ours/ours-privacy-android

## Resources

- [Android Library Publishing](https://developer.android.com/studio/projects/android-library#publish)
- [Maven Central Publishing](https://central.sonatype.org/publish/publish-guide/)
- [Gradle Publishing Plugin](https://docs.gradle.org/current/userguide/publishing_maven.html)
- [GPG Signing](https://docs.gradle.org/current/userguide/signing_plugin.html)