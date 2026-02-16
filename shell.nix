{ pkgs ? import <nixpkgs> {} }:
pkgs.mkShell {
  packages = with pkgs; [
    babashka
    nodejs
    tmux
    curl
    jq
    python3
    ripgrep
  ];

  shellHook = ''
    export PATH="$PWD:$PWD/scripts/deploy:$PATH"
    echo "coggy dev shell ready"
    echo "  node:  $(node --version 2>/dev/null || echo missing)"
    echo "  bb:    $(bb --version 2>/dev/null || echo missing)"
    echo "  coggy: $(command -v coggy || echo missing)"
  '';
}
