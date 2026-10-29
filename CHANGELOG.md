# Zmiany

## 1.0.1

- **Naprawiono** błąd SMB „Key.getEncoded() on null” (np. WD My Cloud): kryptografia przez BouncyCastle, podpisywanie tylko gdy serwer wymaga, puste konto = gość.
- **Łączenie z NAS-em**: serwery w sieci wyszukiwane automatycznie, a udział i folder wybiera się w przeglądarce (z tworzeniem nowego folderu) zamiast wpisywać ścieżkę.
- **Prostsze ustawienia**: port, domena i Wake-on-LAN w opcjach zaawansowanych, nazwy miejsc i zadań uzupełniane automatycznie, rzadkie opcje zadania pod „Więcej opcji”, krótszy ekran ustawień.

## 1.0.0

Pierwsze wydanie OpenSync: szyfrowane kopie zapasowe telefonu w sieci domowej, w stylu OpenPDF.

- **Szyfrowanie w telefonie**: AES-256-GCM, PBKDF2 (600 000 iteracji), hasła w Android Keystore, zmiana hasła bez ponownego szyfrowania.
- **Deduplikacja**: kolejne kopie wysyłają tylko zmienione fragmenty, a każda kopia daje się przywrócić w całości.
- **Retencja**: ostatnie, godzinowe, dzienne, tygodniowe, miesięczne i roczne kopie, okno N dni, przypinanie, gotowe warianty.
- **Cele**: SMB 2/3, SFTP (klucz hosta TOFU), WebDAV, folder lub dysk USB; wyszukiwanie serwerów w sieci i Wake-on-LAN.
- **Źródła**: SMS i MMS (zgodne z OpenSMS), połączenia, kontakty (vCard), kalendarze (ICS), zdjęcia, filmy, audio, pobrane, wybrane foldery, aplikacje z APK.
- **Harmonogram**: co N godzin, codziennie, co tydzień, po nowym zdjęciu; warunki Wi-Fi, domowej sieci, ładowania, baterii i bezczynności.
- **Przywracanie**: przeglądanie kopii, wyszukiwanie, porównanie wersji, przywracanie całości lub wybranych plików, otwieranie pojedynczych plików.
- **Weryfikacja** po co N-tej kopii i pełna na żądanie; historia z ostrzeżeniami.
- **Narzędzie na komputer** `opensync-restore.py`: odczyt i przywracanie kopii bez telefonu.
- Eksport i import ustawień, automatyczne aktualizacje z GitHub Releases.
