// The changelog is a build-time artifact: entries are authored here (newest first) and bundled
// into the SPA, so it can only change with a deploy — never at runtime. The newest entry's
// version must equal APP_VERSION in ./version.ts (the shell's eager import — this file stays
// out of the main bundle): a release adds the entry AND bumps that literal; entries.test.ts
// pins the pair. Bodies are markdown, one per language (content, not chrome — hence not in
// locales/); keep the phrases tests assert on in plain text runs, and follow the Polish style
// conventions (inclusive slash forms, active voice).
interface ChangelogEntry {
  version: string;
  /** Release date, YYYY-MM-DD. Keep the array strictly descending by date. */
  date: string;
  /** Markdown body, English. */
  en: string;
  /** Markdown body, Polish. */
  pl: string;
}

export const CHANGELOG: readonly ChangelogEntry[] = [
  {
    version: "1.6.0",
    date: "2026-08-30",
    en: `Try an import before committing.

- The import page gains a **Check** button: the same per-document report the real import gives — would be created, would carry findings, already exists, invalid — computed without storing anything.
- Happy with the preview? Import follows with one click on the same batch.`,
    pl: `Wypróbuj import, zanim się zdecydujesz.

- Strona importu zyskuje przycisk **Sprawdź**: ten sam raport dla każdego dokumentu, który daje prawdziwy import — zostałby utworzony, miałby zastrzeżenia, już istnieje, nieprawidłowy — policzony bez zapisywania czegokolwiek.
- Podgląd się zgadza? Import robisz jednym kliknięciem na tej samej partii.`,
  },
  {
    version: "1.5.0",
    date: "2026-08-30",
    en: `Toadie speaks your language.

- Your language is now a saved preference: pick it once in the header menu and every device you sign in on comes up in it.
- Every email Toadie sends — the password reset, the sign-in code — arrives in your language too.
- Admins can set a language when creating a user and fix it later in the user editor.`,
    pl: `Toadie mówi w Twoim języku.

- Język jest teraz zapisanym ustawieniem: wybierz go raz w menu nagłówka, a każde urządzenie, na którym się logujesz, uruchomi się w nim.
- Każdy e-mail od Toadie — reset hasła, kod logowania — również przychodzi w Twoim języku.
- Administratorzy/ki mogą ustawić język przy zakładaniu konta i poprawić go później w edytorze użytkownika/czki.`,
  },
  {
    version: "1.4.0",
    date: "2026-08-29",
    en: `Kind pills, always at hand.

- The **Kind** pills moved out of the collapsible filter panel — they now sit right above the Files list, the Hierarchy tree, and the Graph canvas, always visible.
- They work as a visibility switch: every kind starts ON, each view remembers your choice, and with all pills off the view simply shows nothing.`,
    pl: `Pigułki rodzajów zawsze pod ręką.

- Pigułki **Rodzaj** wyprowadziły się ze zwijanego panelu filtrów — siedzą teraz tuż nad listą Plików, drzewem Hierarchii i płótnem Grafu, zawsze widoczne.
- Działają jak przełącznik widoczności: każdy rodzaj startuje włączony, każdy widok pamięta Twój wybór, a przy wszystkich pigułkach wyłączonych widok po prostu nic nie pokazuje.`,
  },
  {
    version: "1.3.0",
    date: "2026-08-29",
    en: `Tidier filters, lists, and menu.

- The **Kind** filter is a row of pills now — pick any combination of kinds at once (Files, Hierarchy, and Graph alike).
- The **Type** filter groups its options by kind, the way tags group by category.
- The Files table drops its Tags column — tags stay a filter, the rows get the width back.
- The Graph page's relation pills carry a caption and speak in relationships ("Owned by", "Part of system") instead of entity names, and the page moved to */graph*.
- The side menu is grouped: **Dictionaries** (Namespaces, Types, Lifecycles) and **Metadata** (Labels, Tags, Annotations).`,
    pl: `Porządek w filtrach, listach i menu.

- Filtr **Rodzaj** to teraz rząd pigułek — wybierasz dowolną kombinację rodzajów naraz (Pliki, Hierarchia i Graf tak samo).
- Filtr **Typ** grupuje opcje według rodzaju, tak jak tagi grupują się według kategorii.
- Tabela Plików traci kolumnę tagów — tagi pozostają filtrem, a wiersze odzyskują szerokość.
- Pigułki relacji na stronie Grafu mają podpis i mówią relacjami („Własność", „Należy do systemu") zamiast nazwami encji, a strona przeniosła się pod */graph*.
- Menu boczne jest pogrupowane: **Słowniki** (Przestrzenie nazw, Typy, Cykle życia) i **Metadane** (Etykiety, Tagi, Adnotacje).`,
  },
  {
    version: "1.2.0",
    date: "2026-08-29",
    en: `Find your files faster.

- The Files list gains **type**, **lifecycle**, **owner**, and **label** filters beside the existing name, namespace, kind, and tag ones — the owner picker understands every way a file may spell its owner, and a label can be filtered by key alone or by chosen values.
- The **Hierarchy** and **Graph** views carry the same full filter panel now, narrowing which files are expanded while everything they reference stays visible.
- Shorter addresses: the app now lives under */files* (the API moved to */api/v1/files* with it).`,
    pl: `Szybciej znajduj swoje pliki.

- Lista Pliki zyskuje filtry **typu**, **cyklu życia**, **właściciela/ki** i **etykiet** obok dotychczasowych: nazwy, przestrzeni nazw, rodzaju i tagu — wybór właściciela/ki rozumie każdy zapis odwołania, a etykietę można filtrować samym kluczem albo wybranymi wartościami.
- Widoki **Hierarchia** i **Graf** mają teraz ten sam pełny panel filtrów, zawężający rozwijane pliki, podczas gdy wszystko, do czego się odwołują, pozostaje widoczne.
- Krótsze adresy: aplikacja mieszka teraz pod */files* (API przeniosło się razem z nią pod */api/v1/files*).`,
  },
  {
    version: "1.1.0",
    date: "2026-08-29",
    en: `Saving with findings, and this very page.

- A save that fails the reference or registry checks now asks for confirmation instead of refusing outright — *Save anyway* stores the file, and the Cross-check page tracks every waived finding until you fix it.
- Import always stores flawed documents ("Created with findings"), so a big batch can land first and be repaired incrementally.
- Cross-check now covers the label, annotation, tag, type, and lifecycle rules too — one workspace health report.
- The changelog you are reading, with the what's-new dot on the version stamp.`,
    pl: `Zapisywanie z zastrzeżeniami — oraz ta właśnie strona.

- Zapis, który nie przechodzi kontroli odwołań lub rejestrów, prosi teraz o potwierdzenie zamiast odmawiać — *Zapisz mimo to* zachowuje plik, a strona Weryfikacja śledzi każde zastrzeżenie, dopóki go nie poprawisz.
- Import zawsze zapisuje wadliwe dokumenty („Utworzono z zastrzeżeniami"), więc duża partia może najpierw trafić do środka, a poprawki robisz stopniowo.
- Weryfikacja obejmuje teraz także reguły etykiet, adnotacji, tagów, typów i cykli życia — jeden raport zdrowia przestrzeni roboczej.
- Historia zmian, którą właśnie czytasz, z kropką nowości na stemplu wersji.`,
  },
  {
    version: "1.0.0",
    date: "2026-08-29",
    en: `The 1.0 milestone.

- The new **Hierarchy** view at the root: the workspace as collapsible containment trees (systems holding components, components holding subcomponents, groups holding members) with the full file operations on every row.
- Clearer navigation: *Files*, *Graph*, and *Hierarchy*.
- A security and consistency check-up across the whole stack: request size limits, hardened login timing, stricter token rules, and uniform error hints.`,
    pl: `Kamień milowy 1.0.

- Nowy widok **Hierarchia** na stronie głównej: przestrzeń robocza jako zwijane drzewa zawierania (systemy z komponentami, komponenty z podkomponentami, grupy z członkiniami/członkami) z pełnymi operacjami na plikach w każdym wierszu.
- Czytelniejsza nawigacja: *Pliki*, *Graf* i *Hierarchia*.
- Przegląd bezpieczeństwa i spójności całego stosu: limity wielkości żądań, utwardzone czasy logowania, ostrzejsze reguły tokenów i ujednolicone podpowiedzi błędów.`,
  },
  {
    version: "0.9.0",
    date: "2026-08-29",
    en: `The last three registries, and list polish.

- Per-kind **type** dictionaries, the global **lifecycles** list, and the **annotation key** registry — every constrained field now has its admin page.
- No entity may reference itself any more.
- The Files list shows titles and tags, filters by tag, and bundles row actions under one Operations menu.`,
    pl: `Trzy ostatnie rejestry i szlif listy.

- Słowniki **typów** per rodzaj, globalna lista **cykli życia** i rejestr **kluczy adnotacji** — każde ograniczone pole ma teraz swoją stronę administracyjną.
- Żadna encja nie może już odwoływać się do samej siebie.
- Lista Pliki pokazuje tytuły i tagi, filtruje po tagu i zbiera akcje wiersza w jednym menu Operacje.`,
  },
  {
    version: "0.8.0",
    date: "2026-08-28",
    en: `Per-user feature flags and a second factor.

- Administrators can enable or disable features per user, singly or in bulk.
- Opt-in email MFA: with the flag on, signing in asks for a 6-digit code delivered to your inbox.`,
    pl: `Flagi funkcji per użytkowniczka/użytkownik i drugi składnik.

- Administratorki/administratorzy mogą włączać i wyłączać funkcje pojedynczo lub hurtowo.
- Opcjonalne MFA e-mailem: z włączoną flagą logowanie prosi o 6-cyfrowy kod dostarczony na skrzynkę.`,
  },
  {
    version: "0.7.0",
    date: "2026-08-28",
    en: `Tags, strict references, and email.

- Admin-curated **tag categories**: files may carry only registered tags allowed for their kind.
- Every entity reference must now resolve to a stored entity of an allowed kind, and the pickers insert full identities.
- Outbound email arrived, and with it self-service password reset from the sign-in page.`,
    pl: `Tagi, ścisłe odwołania i e-mail.

- **Kategorie tagów** pod opieką administracji: pliki mogą nosić tylko zarejestrowane tagi dozwolone dla ich rodzaju.
- Każde odwołanie do encji musi teraz rozwiązywać się do zapisanej encji dozwolonego rodzaju, a wybieraki wstawiają pełne tożsamości.
- Pojawił się e-mail wychodzący, a wraz z nim samodzielny reset hasła ze strony logowania.`,
  },
  {
    version: "0.6.0",
    date: "2026-08-27",
    en: `Namespaces and labels get their registries.

- The **namespaces** dictionary: catalog files live only in admin-defined namespaces, with one flagged as the default for blank entries.
- The **label** registry: each label is a key with a closed value list and the kinds it applies to.`,
    pl: `Przestrzenie nazw i etykiety dostają rejestry.

- Słownik **przestrzeni nazw**: pliki katalogu żyją tylko w przestrzeniach zdefiniowanych przez administrację, z jedną oznaczoną jako domyślna dla pustych wpisów.
- Rejestr **etykiet**: każda etykieta to klucz z zamkniętą listą wartości i rodzajami, których dotyczy.`,
  },
  {
    version: "0.5.0",
    date: "2026-08-23",
    en: `The YAML round trip.

- Import a multi-document catalog-info.yaml — each document reports its own result row.
- Export the whole workspace (or one namespace) as a single YAML file.
- Fetch a catalog file straight from a public URL, with the server guarding the request.`,
    pl: `Podróż YAML w obie strony.

- Import wielodokumentowego catalog-info.yaml — każdy dokument raportuje własny wiersz wyniku.
- Eksport całej przestrzeni roboczej (albo jednej przestrzeni nazw) jako pojedynczy plik YAML.
- Pobieranie pliku katalogu wprost z publicznego adresu URL, z żądaniem chronionym po stronie serwera.`,
  },
  {
    version: "0.4.0",
    date: "2026-08-23",
    en: `All seven kinds, and people management.

- The editor speaks every landscape kind: Component, API, System, Domain, Resource, Group, and User, with per-kind fields and rules.
- Reference fields suggest the stored entities they may point at.
- User management for administrators, with client-generated one-time-reveal passwords.`,
    pl: `Wszystkie siedem rodzajów i zarządzanie ludźmi.

- Edytor zna każdy rodzaj krajobrazu: Component, API, System, Domain, Resource, Group i User, z polami i regułami per rodzaj.
- Pola odwołań podpowiadają zapisane encje, na które mogą wskazywać.
- Zarządzanie użytkowniczkami/użytkownikami dla administracji, z hasłami generowanymi po stronie klienta i pokazywanymi jednorazowo.`,
  },
  {
    version: "0.3.0",
    date: "2026-08-23",
    en: `Cross-checking and the graph.

- The Cross-check report resolves every reference in the workspace, and the editor gained a live findings panel.
- The relationship graph draws the whole workspace as nodes and reference edges.`,
    pl: `Weryfikacja i graf.

- Raport Weryfikacja rozwiązuje każde odwołanie w przestrzeni roboczej, a edytor zyskał panel zastrzeżeń na żywo.
- Graf relacji rysuje całą przestrzeń roboczą jako węzły i krawędzie odwołań.`,
  },
  {
    version: "0.2.0",
    date: "2026-08-23",
    en: `The visual catalog editor.

- Create Backstage Component files in a form with a live YAML preview, browse them in a filterable list, and download the rendered catalog-info.yaml.`,
    pl: `Wizualny edytor katalogu.

- Tworzenie plików Component Backstage w formularzu z podglądem YAML na żywo, przeglądanie ich na filtrowanej liście i pobieranie wyrenderowanego catalog-info.yaml.`,
  },
  {
    version: "0.1.0",
    date: "2026-08-22",
    en: `The skeleton.

- The full stack: a Kotlin/Ktor server, a React SPA, sign-in with token refresh, a bootstrap administrator, quality gates on every layer, and a one-command Docker Compose stack.`,
    pl: `Szkielet.

- Pełny stos: serwer Kotlin/Ktor, SPA w React, logowanie z odświeżaniem tokenów, startowe konto administracyjne, bramki jakości na każdej warstwie i stos Docker Compose na jedną komendę.`,
  },
];
