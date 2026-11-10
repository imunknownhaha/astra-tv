Name:           astra
Version:        0.1.3
Release:        1%{?dist}
Summary:        Native IPTV library and player for Linux
License:        MIT
URL:            https://github.com/imunknownhaha/astra-tv
Source0:        astra-%{version}.tar.gz
BuildArch:      noarch
Requires:       python3 >= 3.10
Requires:       python3-gobject
Requires:       gtk3 >= 3.24
Requires:       python3-cryptography
Requires:       python3-ijson
Requires:       python3-defusedxml
Requires:       mpv >= 0.38
AutoReqProv:     no

%description
Astra imports user-provided M3U and Xtream playlists, browses live TV, movies,
series and XMLTV guides, and preserves favorites and viewing progress per
profile. Native GTK browsing and one mpv playback window keep resource use low.
No channels, subscriptions or media are included.

%prep
%setup -q

%build
# Python sources are installed without host-specific bytecode.

%install
install -d %{buildroot}%{_datadir}/astra/astra_linux
install -m 0644 astra_linux/*.py %{buildroot}%{_datadir}/astra/astra_linux/
install -Dm 0755 packaging/astra %{buildroot}%{_bindir}/astra
install -Dm 0644 packaging/astra.desktop %{buildroot}%{_datadir}/applications/io.github.imunknownhaha.Astra.desktop
install -Dm 0644 packaging/io.github.imunknownhaha.Astra.svg %{buildroot}%{_datadir}/icons/hicolor/scalable/apps/io.github.imunknownhaha.Astra.svg

%files
%license LICENSE
%doc README.md
%{_bindir}/astra
%{_datadir}/astra/
%{_datadir}/applications/io.github.imunknownhaha.Astra.desktop
%{_datadir}/icons/hicolor/scalable/apps/io.github.imunknownhaha.Astra.svg

%changelog
* Tue Nov 10 2026 Astra Contributors - 0.1.3-1
- Initial native Fedora desktop preview.
