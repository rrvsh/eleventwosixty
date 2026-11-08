{
  description = "Build and headless-test tooling for eleventwosixty";

  inputs.nixpkgs.url = "github:NixOS/nixpkgs/nixos-unstable";

  outputs = { nixpkgs, ... }:
    let
      system = "aarch64-darwin";
      pkgs = import nixpkgs {
        inherit system;
        config = {
          allowUnfree = true;
          android_sdk.accept_license = true;
        };
      };
      androidComposition = pkgs.androidenv.composeAndroidPackages {
        platformVersions = [ "35" ];
        buildToolsVersions = [ "34.0.0" "35.0.0" ];
        abiVersions = [ "arm64-v8a" ];
        systemImageTypes = [ "default" ];
        includeEmulator = true;
        includeSystemImages = true;
        includeSources = false;
        includeNDK = false;
      };
      androidSdk = androidComposition.androidsdk;
    in {
      devShells.${system}.default = pkgs.mkShell {
        packages = [
          pkgs.jdk17_headless
          androidSdk
        ];

        ANDROID_HOME = "${androidSdk}/libexec/android-sdk";
        ANDROID_SDK_ROOT = "${androidSdk}/libexec/android-sdk";
        JAVA_HOME = "${pkgs.jdk17_headless}";
      };
    };
}
