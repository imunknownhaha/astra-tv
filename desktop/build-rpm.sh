#!/usr/bin/env bash
set -euo pipefail
source_dir="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
build_dir="${1:-$source_dir/build/rpm}"
version="$(awk '/^Version:/ { print $2; exit }' "$source_dir/packaging/astra.spec")"
mkdir -p "$build_dir"/{BUILD,BUILDROOT,RPMS,SOURCES,SPECS,SRPMS,tmp}
build_dir="$(cd "$build_dir" && pwd)"
tar --exclude=__pycache__ --exclude=build --exclude='*.pyc' --transform="s,^,astra-$version/," -czf "$build_dir/SOURCES/astra-$version.tar.gz" -C "$source_dir" astra_linux packaging LICENSE README.md tests
rpmbuild --define "_topdir $build_dir" --define "_tmppath $build_dir/tmp" --define 'dist %{nil}' --define '_build_id_links none' --define '__os_install_post %{nil}' -ba "$source_dir/packaging/astra.spec"
