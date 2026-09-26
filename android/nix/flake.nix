{
  description = "Sniptube Android build shell (configuration only; no SDK/build caches)";

  inputs.nixpkgs.url = "github:NixOS/nixpkgs/nixpkgs-unstable";

  outputs = { self, nixpkgs }:
    let
      system = "x86_64-linux";
      pkgs = import nixpkgs { inherit system; };
    in {
      devShells.${system}.default = pkgs.mkShell {
        packages = with pkgs; [
          curl
          jdk17_headless
          unzip
        ];
      };
    };
}
