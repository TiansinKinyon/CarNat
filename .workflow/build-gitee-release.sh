#!/usr/bin/env bash
set +x
set -euo pipefail

version="$(sed -nE 's/^[[:space:]]*versionName = "([^"]+)"[[:space:]]*$/\1/p' app/build.gradle.kts)"
if [[ ! "$version" =~ ^[0-9]+\.[0-9]+\.[0-9]+$ ]]; then
  echo 'Expected a stable versionName in app/build.gradle.kts.' >&2
  exit 1
fi
tag="v$version"
if [[ "${GITEE_REF:-}" == refs/tags/* && "$GITEE_REF" != "refs/tags/$tag" ]]; then
  echo "Gitee trigger tag does not match Android version $tag." >&2
  exit 1
fi
git fetch --no-tags origin "refs/tags/$tag"
if [[ "$(git rev-parse 'FETCH_HEAD^{commit}')" != "$(git rev-parse HEAD)" ]]; then
  echo "Checked-out commit does not match Gitee tag $tag." >&2
  exit 1
fi

: "${ANDROID_KEYSTORE_BASE64:?Set ANDROID_KEYSTORE_BASE64 in Gitee Go}"
: "${ANDROID_KEYSTORE_PASSWORD:?Set ANDROID_KEYSTORE_PASSWORD in Gitee Go}"
: "${ANDROID_KEY_ALIAS:?Set ANDROID_KEY_ALIAS in Gitee Go}"
: "${ANDROID_KEY_PASSWORD:?Set ANDROID_KEY_PASSWORD in Gitee Go}"

work_dir="$(mktemp -d)"
trap 'rm -rf -- "$work_dir"' EXIT

# Use the same JDK 25 distribution selected by the Gradle daemon toolchain.
jdk_url="$(sed -n 's/^toolchainUrl.LINUX.X86_64=//p' gradle/gradle-daemon-jvm.properties | tr -d '\\\r')"
if [[ "$(uname -m)" != x86_64 || "$jdk_url" != https://api.foojay.io/disco/v3.0/ids/*/redirect ]]; then
  echo 'Expected the configured Linux x86-64 JDK download URL.' >&2
  exit 1
fi
mkdir -p "$work_dir/jdk"
curl --fail --silent --show-error --location --retry 3 "$jdk_url" -o "$work_dir/jdk.tar.gz"
tar -xzf "$work_dir/jdk.tar.gz" -C "$work_dir/jdk" --strip-components=1
export JAVA_HOME="$work_dir/jdk"
export PATH="$JAVA_HOME/bin:$PATH"
java -version

export ANDROID_HOME="$work_dir/android-sdk"
export ANDROID_SDK_ROOT="$ANDROID_HOME"
tools_version=15859902
tools_sha256=4e4c464f145a7512b57d088ac6c278c03c9eea610886b35a5e0804e74eedf583
tools_zip="$work_dir/commandlinetools.zip"
curl --fail --silent --show-error --location --retry 3 \
  "https://dl.google.com/android/repository/commandlinetools-linux-${tools_version}_latest.zip" -o "$tools_zip"
printf '%s  %s\n' "$tools_sha256" "$tools_zip" | sha256sum --check --status
mkdir -p "$ANDROID_HOME/cmdline-tools"
unzip -q "$tools_zip" -d "$work_dir"
mv "$work_dir/cmdline-tools" "$ANDROID_HOME/cmdline-tools/latest"
sdkmanager="$ANDROID_HOME/cmdline-tools/latest/bin/sdkmanager"
# sdkmanager's result matters here. yes can exit with SIGPIPE after licenses are accepted.
(set +o pipefail; yes | "$sdkmanager" --licenses >/dev/null)
"$sdkmanager" 'platforms;android-37.0' 'build-tools;36.0.0'

# Gitee's shared Gradle home injects an init.gradle that adds project repositories.
# A fresh user home keeps the build's repository policy and toolchain settings intact.
export GRADLE_USER_HOME="$work_dir/gradle-home"
env -u ANDROID_KEYSTORE_BASE64 -u ANDROID_KEYSTORE_PASSWORD -u ANDROID_KEY_ALIAS -u ANDROID_KEY_PASSWORD \
  bash ./gradlew --no-daemon testDebugUnitTest assembleRelease

keystore="$work_dir/release.jks"
printf '%s' "$ANDROID_KEYSTORE_BASE64" | base64 --decode > "$keystore"
apksigner="$ANDROID_HOME/build-tools/36.0.0/apksigner"
mkdir -p release-artifacts
apk="release-artifacts/CarNat-Xiaomi-${tag}.apk"
"$apksigner" sign \
  --ks "$keystore" \
  --ks-key-alias "$ANDROID_KEY_ALIAS" \
  --ks-pass env:ANDROID_KEYSTORE_PASSWORD \
  --key-pass env:ANDROID_KEY_PASSWORD \
  --out "$apk" \
  app/build/outputs/apk/release/app-release-unsigned.apk
"$apksigner" verify --verbose "$apk"
printf 'CARNAT_RELEASE_TAG=%s\n' "$tag" >> GITEE_PARAMS
