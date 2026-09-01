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
    version: "1.16.0",
    date: "2026-09-01",
    en: `**The filters now decide what you see — on Files, Hierarchy and the Graph alike.**

- Filtering the Graph or the Hierarchy to one kind used to draw the neighbours those entities pointed at as well: pick **Domain** and the owning Groups came along. Now the pills and filters select **which entities are shown**, and the Graph shows exactly the entities the Files list would return for the same query.
- A relation is drawn only when **both of its ends are shown**, so hiding a kind takes its edges with it. The relation chips still govern relations only — switching one off never removes an entity.
- The Hierarchy follows the same rule: with a container's kind filtered out, its children sit flat at the root rather than nesting under something you asked not to see.
- A referenced entity that does not exist (a **missing** node) is judged the same way — by kind, namespace and name — so it appears when you are looking at its kind, and stays out when you are not.
- One thing is no longer drawn at all: references to kinds Toadie does not store, such as a Backstage Template. No kind pill can select them, so they cannot honestly be shown as part of a filtered view.`,
    pl: `**To filtry decydują teraz o tym, co widzisz — tak samo na Plikach, Hierarchii i Grafie.**

- Zawężenie Grafu albo Hierarchii do jednego rodzaju rysowało dotąd również sąsiadów, na które te encje wskazywały: po wybraniu **Domeny** dołączały do niej Grupy będące właścicielami. Teraz pigułki i filtry wybierają, **które encje są pokazywane**, a Graf pokazuje dokładnie te encje, które lista Plików zwróciłaby dla tego samego zapytania.
- Relacja jest rysowana tylko wtedy, gdy **oba jej końce są pokazywane**, więc ukrycie rodzaju zabiera ze sobą jego krawędzie. Pigułki relacji nadal rządzą wyłącznie relacjami — wyłączenie którejś nigdy nie usuwa encji.
- Hierarchia działa tak samo: gdy rodzaj kontenera jest odfiltrowany, jego dzieci stoją płasko w korzeniu, zamiast zagnieżdżać się pod czymś, czego nie chcesz widzieć.
- Encja, do której istnieje odwołanie, ale która nie istnieje (węzeł **brakujący**), jest oceniana tak samo — po rodzaju, przestrzeni nazw i nazwie — więc pojawia się, gdy patrzysz na jej rodzaj, i nie pojawia się, gdy nie patrzysz.
- Jedno przestało być rysowane zupełnie: odwołania do rodzajów, których Toadie nie przechowuje, na przykład do szablonu Backstage. Żadna pigułka rodzaju ich nie obejmuje, więc nie da się ich uczciwie pokazać jako części odfiltrowanego widoku.`,
  },
  {
    version: "1.15.0",
    date: "2026-09-01",
    en: `**Every catalog file now keeps a history of its own changes.**

- Open a file's editor and you will find a **History** section under the form: who changed what, and when — one entry per creation, edit, repo sync and deletion, newest first.
- The history is **field by field**. An entry names the fields the save touched, and each one that has values to show gets its own line: an owner reads \`group:default/platform -> group:default/payments\`, tags read \`+billing -legacy\`. Labels and annotations name the exact entry that moved.
- Long free text — a description or an API definition — is recorded as the bare fact that it changed, never as its content, so the trail stays readable and small.
- A save that changed nothing records nothing. A sync is recorded even when the repo copy turned out to match, because pulling it is itself an event worth seeing.`,
    pl: `**Każdy plik katalogu ma teraz własną historię zmian.**

- Po otwarciu edytora pliku pod formularzem znajdziesz sekcję **Historia**: kto co zmienił i kiedy — po jednym wpisie na utworzenie, edycję, synchronizację z repozytorium i usunięcie, od najnowszych.
- Historia jest **pole po polu**. Wpis wymienia pola, których dotyczył zapis, a każde, które ma co pokazać, dostaje własną linię: właściciel to \`group:default/platform -> group:default/payments\`, tagi to \`+billing -legacy\`. Etykiety i adnotacje wskazują dokładnie ten wpis, który się zmienił.
- Długi tekst — opis albo definicja API — jest zapisywany wyłącznie jako sam fakt zmiany, nigdy jako treść, więc historia zostaje czytelna i lekka.
- Zapis, który niczego nie zmienił, nie zostawia wpisu. Synchronizacja zostawia go zawsze, nawet gdy kopia z repozytorium okazała się identyczna — samo jej pobranie warto widzieć.`,
  },
  {
    version: "1.14.3",
    date: "2026-09-01",
    en: `**A consistency pass across the whole app.**

- **Colours now say one thing everywhere**: red means blocked or destructive, orange means "saves after confirming", gray is neutral. The Errors report no longer paints every row red — waivable findings are orange, and a missing source reference (which blocks nothing) is gray. The Sync and Overwrite confirmations turned red: they overwrite the stored copy.
- **The import report links stored rows** to their editors — the "Created with findings" ones are exactly the files you go on to fix — and on **Feature flags** a user's name opens their features editor.
- Sortable table headers announce their sort direction to assistive technology, counts read naturally ("1 file checked", not "1 files"), and a duplicate key typed into a registry dialog is now flagged on the field itself.`,
    pl: `**Przegląd spójności całej aplikacji.**

- **Kolory mówią wszędzie to samo**: czerwony znaczy zablokowane lub nieodwracalne, pomarańczowy — "zapisze się po potwierdzeniu", szary jest neutralny. Raport Błędów nie maluje już każdego wiersza na czerwono — ustalenia do zatwierdzenia są pomarańczowe, a brak odniesienia do źródła (który niczego nie blokuje) jest szary. Potwierdzenia Synchronizacji i Nadpisania zrobiły się czerwone: nadpisują zapisaną kopię.
- **Raport importu linkuje zapisane wiersze** do ich edytorów — te "Utworzone z ustaleniami" to dokładnie pliki, które zaraz poprawisz — a na **Flagach funkcji** nazwa osoby otwiera jej edytor funkcji.
- Sortowalne nagłówki tabel ogłaszają kierunek sortowania technologiom asystującym, liczebniki odmieniają się naturalnie, a zduplikowany klucz wpisany w okno rejestru jest teraz oznaczany na samym polu.`,
  },
  {
    version: "1.14.2",
    date: "2026-08-31",
    en: `**Click a name to open the file.**

- On **Files** and **Hierarchy** an entity's name now opens its editor, the way it already did on the Graph and the Errors report. The Operations menu still gets you there too.
- It is an ordinary link, so it opens in a new tab with cmd/ctrl-click or the middle mouse button, and it is reachable from the keyboard.
- In the tree, placeholders for entities that are only referenced — never stored — stay plain: there is nothing to open.`,
    pl: `**Kliknij nazwę, żeby otworzyć plik.**

- Na **Plikach** i w **Hierarchii** nazwa encji otwiera teraz jej edytor — tak jak już działo się to na Grafie i w raporcie Błędów. Menu Operacje nadal też tam prowadzi.
- To zwykły odnośnik, więc otworzysz go w nowej karcie przez cmd/ctrl-kliknięcie albo środkowy przycisk myszy, i dosięgniesz go z klawiatury.
- W drzewie zastępniki encji, które są tylko przywoływane — nigdy zapisane — pozostają zwykłym tekstem: nie ma czego otwierać.`,
  },
  {
    version: "1.14.1",
    date: "2026-08-31",
    en: `**In fields that hold several values, the bad entry is now marked itself.**

- **Depends on**, **Provides APIs**, **Tags** and their siblings show each value as a pill. Until now a problem with one of them tinted the whole field, and you had to read the message underneath to work out which pill it meant.
- The offending pill is now tinted too — **orange** when it is a finding (the file still saves, after confirming), **red** when it breaks the rules and blocks the save. Hover it for the reason.
- When a field is blocked, **every** malformed entry is marked, not only the first one the message happens to name.`,
    pl: `**W polach z wieloma wartościami błędny wpis jest teraz oznaczony sam.**

- **Zależy od**, **Udostępnia API**, **Tagi** i pokrewne pokazują każdą wartość jako pigułkę. Do tej pory problem z jedną z nich barwił całe pole i trzeba było czytać komunikat pod spodem, żeby ustalić, o którą pigułkę chodzi.
- Teraz barwiona jest też sama pigułka — **pomarańczowa**, gdy to ustalenie (plik i tak się zapisze, po potwierdzeniu), **czerwona**, gdy łamie reguły i blokuje zapis. Najedź na nią, żeby poznać powód.
- Gdy pole jest zablokowane, oznaczone są **wszystkie** błędne wpisy, a nie tylko ten pierwszy, który akurat wymienia komunikat.`,
  },
  {
    version: "1.14.0",
    date: "2026-08-31",
    en: `**Problems now show on the field, not just in the list.**

- Every finding the editor reports — a reference that resolves to nothing, a tag or label the registry doesn't allow, a type outside its dictionary — is marked **on the control that caused it**, while the Findings panel keeps the full list.
- **Two colours, two meanings.** Red means the save is blocked (a required field left empty, a name that breaks the rules). Orange means the file still saves, after confirming — that is the same orange used elsewhere for "stored, with findings".
- Required fields are flagged **when you leave them**, instead of staying silent until you press Save.`,
    pl: `**Problemy widać teraz przy polu, nie tylko na liście.**

- Każde ustalenie zgłaszane przez edytor — odwołanie, które donikąd nie prowadzi, tag lub etykieta niedozwolona przez rejestr, typ spoza słownika — jest oznaczone **przy kontrolce, która je wywołała**, a panel Ustaleń nadal pokazuje pełną listę.
- **Dwa kolory, dwa znaczenia.** Czerwony oznacza, że zapis jest zablokowany (puste pole wymagane, nazwa łamiąca reguły). Pomarańczowy — że plik i tak się zapisze, po potwierdzeniu; to ten sam pomarańczowy, którego używamy dla „zapisane, z ustaleniami".
- Pola wymagane są oznaczane **w chwili opuszczenia pola**, zamiast milczeć aż do naciśnięcia Zapisz.`,
  },
  {
    version: "1.13.1",
    date: "2026-08-31",
    en: `**Fix: the editor now refreshes after Overwrite or Sync.**

- Overwriting a file with a YAML — or syncing it from its source — while its editor was open left the form showing the OLD content.
- Worse, and now fixed too: pressing **Save** afterwards wrote that old content back, silently undoing what you had just done.`,
    pl: `**Poprawka: edytor odświeża się po Nadpisaniu lub Synchronizacji.**

- Nadpisanie pliku YAML-em — albo zsynchronizowanie go ze źródłem — przy otwartym edytorze zostawiało w formularzu STARĄ treść.
- Gorzej, i to też jest naprawione: naciśnięcie **Zapisz** zapisywało wtedy tę starą treść z powrotem, po cichu cofając to, co przed chwilą zrobiłeś/zrobiłaś.`,
  },
  {
    version: "1.13.0",
    date: "2026-08-31",
    en: `**Actions that say what they act on** — and a new way to replace one file.

- **Overwrite with YAML** (new): replace a single file's content by pasting or picking a YAML. You see a line-by-line diff against the stored copy before confirming, and the file's source reference is kept.
- **Download** is now **Export as YAML**, since that is what it produces.
- **Import YAML** is now **Import new YAML** — it only ever adds files, and never overwrites an existing one.
- **Sync from repo** is now **Sync from source**, and it no longer disappears on a file without a source: it is greyed out instead, so you can see the option exists and what it needs.
- **The editor gained the same three actions**, plus a line telling you when the file was last synced and whether it has changed since.
- The bottom **Export YAML** button is gone. It exported the whole workspace rather than a file, which the label never said; per-file export now lives in the row's Operations menu.`,
    pl: `**Akcje, które mówią, na czym działają** — i nowy sposób na podmianę pojedynczego pliku.

- **Nadpisz plikiem YAML** (nowość): zastąp treść jednego pliku, wklejając YAML lub wybierając plik. Przed potwierdzeniem widzisz różnice linia po linii wobec zapisanej kopii, a odwołanie do źródła zostaje zachowane.
- **Pobierz** to teraz **Eksportuj jako YAML** — bo dokładnie to robi.
- **Importuj YAML** to teraz **Importuj nowy YAML** — import wyłącznie dodaje pliki i nigdy nie nadpisuje istniejącego.
- **Synchronizuj z repozytorium** to teraz **Synchronizuj ze źródłem** i nie znika już przy pliku bez źródła: jest wyszarzone, więc widzisz, że opcja istnieje i czego wymaga.
- **Edytor dostał te same trzy akcje**, a do tego informację, kiedy plik był ostatnio synchronizowany i czy od tego czasu się zmienił.
- Dolny przycisk **Eksportuj YAML** zniknął. Eksportował cały obszar roboczy, a nie plik — czego etykieta nie mówiła; eksport pojedynczego pliku jest teraz w menu Operacje w wierszu.`,
  },
  {
    version: "1.12.0",
    date: "2026-08-31",
    en: `**Namespaces on the graph** — the Graph page now shows where one namespace ends and the next begins.

- When the graph spans **more than one namespace**, each is drawn inside its own labelled frame.
- The automatic layout **keeps a namespace's entities together**, so the frames are readable instead of overlapping boxes drawn around scattered nodes.
- In **Manual** layout, a frame follows what you do: drag an entity anywhere and its namespace's frame stretches to keep containing it. Dragging never changes which namespace something belongs to — that comes from the file itself.
- A workspace using a single namespace looks exactly as before: one frame around everything would say nothing, so none is drawn.`,
    pl: `**Przestrzenie nazw na grafie** — strona Graf pokazuje teraz, gdzie kończy się jedna przestrzeń nazw, a zaczyna kolejna.

- Kiedy graf obejmuje **więcej niż jedną przestrzeń nazw**, każda jest rysowana we własnej, opisanej ramce.
- Automatyczny układ **trzyma encje jednej przestrzeni nazw razem**, dzięki czemu ramki są czytelne, zamiast obejmować rozrzucone węzły.
- W układzie **Ręcznym** ramka podąża za Tobą: przeciągnij encję gdziekolwiek, a ramka jej przestrzeni nazw rozciągnie się, żeby ją objąć. Przeciąganie nigdy nie zmienia przynależności — ta wynika z samego pliku.
- Obszar roboczy z jedną przestrzenią nazw wygląda dokładnie jak dotąd: ramka wokół wszystkiego nic by nie mówiła, więc jej nie ma.`,
  },
  {
    version: "1.11.3",
    date: "2026-08-31",
    en: `**Clearer graph nodes** — the boxes on the Graph page say something useful now.

- Each node shows its **type** (service, database, openapi…) where the kind has one, instead of the namespace — which read "default" on almost every file.
- **Long names no longer disappear under the kind badge.** The name now shortens with an ellipsis and stops where the badge begins.
- **Hover a node's name** for the full picture: name, namespace, title and tags.`,
    pl: `**Czytelniejsze węzły grafu** — prostokąty na stronie Graf wreszcie coś mówią.

- Każdy węzeł pokazuje swój **typ** (service, database, openapi…) tam, gdzie dany rodzaj go ma, zamiast przestrzeni nazw — która przy niemal każdym pliku brzmiała „default".
- **Długie nazwy nie chowają się już pod odznaką rodzaju.** Nazwa skraca się wielokropkiem i kończy tam, gdzie zaczyna się odznaka.
- **Najedź na nazwę węzła**, żeby zobaczyć całość: nazwę, przestrzeń nazw, tytuł i tagi.`,
  },
  {
    version: "1.11.2",
    date: "2026-08-31",
    en: `**A vocabulary out of the box** — new environments no longer start with empty registries.

- **Labels, tags and annotation keys ship filled in.** Until now all three started empty, which meant no file could carry a label, a tag or an annotation until an administrator defined one. There are now eight label keys (exposure, hosting model, data classification, GDPR, PCI DSS, criticality tier, support mode, technology status), four tag categories (Languages, Framework, Database, Events) and four \`backstage.io\` annotation keys.
- **Types and lifecycles are curated.** Each kind's type list is tuned to a real landscape, and **sunsetting** joins the lifecycles, which now read in order: experimental → production → sunsetting → deprecated.
- **A second namespace, \`external\`**, for the systems you depend on but do not own. \`default\` stays the default.
- **A sample landscape to load.** \`sample-data/catalog-info.yaml\` holds 34 entities across all seven kinds, speaking only the seeded vocabulary — import it to see the Files list, Graph, Hierarchy and Errors report with something real in them.
- Administrators keep full control: every seeded entry is an ordinary row that can be edited, reordered or removed.`,
    pl: `**Słownictwo od razu po instalacji** — nowe środowiska nie startują już z pustymi rejestrami.

- **Etykiety, tagi i klucze adnotacji są od razu wypełnione.** Dotąd wszystkie trzy startowały puste, więc żaden plik nie mógł nieść etykiety, tagu ani adnotacji, dopóki administrator czegoś nie zdefiniował. Teraz jest osiem kluczy etykiet (ekspozycja, model hostingu, klasyfikacja danych, RODO, PCI DSS, poziom krytyczności, tryb wsparcia, status technologii), cztery kategorie tagów (Languages, Framework, Database, Events) i cztery klucze adnotacji \`backstage.io\`.
- **Typy i cykle życia są wykuratorowane.** Lista typów każdego rodzaju jest dopasowana do realnego krajobrazu, a do cykli życia dołącza **sunsetting** — kolejność czyta się teraz jako: experimental → production → sunsetting → deprecated.
- **Druga przestrzeń nazw, \`external\`**, dla systemów, od których zależysz, ale ich nie posiadasz. \`default\` pozostaje domyślna.
- **Przykładowy krajobraz do wczytania.** \`sample-data/catalog-info.yaml\` zawiera 34 encje wszystkich siedmiu rodzajów, mówiące wyłącznie zasianym słownictwem — zaimportuj go, żeby zobaczyć listę Plików, Graf, Hierarchię i raport Błędów z realną zawartością.
- Administratorzy zachowują pełną kontrolę: każdy zasiany wpis to zwykły wiersz, który można edytować, przestawić lub usunąć.`,
  },
  {
    version: "1.11.1",
    date: "2026-08-30",
    en: `**Quality pass** — a full check-up of the new sync feature and its surroundings.

- The Sync-from-repo dialog can no longer be dismissed mid-sync (Esc or a click outside used to lose the result), confirms faster, and reads better with a keyboard and screen reader (the diff pane is focusable and announced).
- Clearer wording: the dialog now says "stored copy" and "Toadie" instead of "DB", and finding counts read naturally in both languages.
- Timestamp columns on the Files list are consistent: both Updated and Last sync show the precise date and time on hover.
- Behind the scenes: a successful fetch from a repository URL is now audit-logged like a blocked one, and the audit trail uses one consistent field for waived findings.`,
    pl: `**Przegląd jakości** — pełna kontrola nowej synchronizacji i jej otoczenia.

- Okna Synchronizacji z repozytorium nie da się już zamknąć w trakcie synchronizacji (Esc lub kliknięcie obok potrafiło zgubić wynik), potwierdzenie działa szybciej, a obsługa klawiaturą i czytnikiem ekranu jest lepsza (panel różnic da się sfokusować i jest odczytywany).
- Czytelniejsze teksty: okno mówi teraz o „zapisanej kopii" i „Toadie" zamiast o „bazie", a liczby ustaleń odmieniają się naturalnie w obu językach.
- Kolumny z datami na liście Plików są spójne: zarówno Zaktualizowano, jak i Ostatnia synchronizacja pokazują dokładną datę i godzinę po najechaniu.
- Od zaplecza: udane pobranie z adresu repozytorium trafia teraz do dziennika audytu tak samo jak zablokowane, a dziennik używa jednego spójnego pola dla pominiętych ustaleń.`,
  },
  {
    version: "1.11.0",
    date: "2026-08-30",
    en: `**Source references & repo sync** — tie each file to its copy in a Git repository.

- Every file can carry a **source file URL** (its copy in a GitLab/GitHub repo): set it in the editor's new Source section, or import from a URL — imported files get the reference automatically and start synced.
- The Files list shows a new **Last sync** column (sortable): no source, never synced, or how long ago — with a "Local changes" marker when the DB copy moved since.
- **Sync from repo** in the row's Operations menu fetches the repo copy, shows which side changed (repo, DB, or both) and a line-by-line diff, and — after your confirmation — overwrites the DB copy. Syncing the other way (DB → repo) stays in your hands.
- A file without a source reference shows up on the Errors report as **No source reference** (its own filter pill).`,
    pl: `**Odnośniki do źródeł i synchronizacja z repozytorium** — powiąż każdy plik z jego kopią w repozytorium Git.

- Każdy plik może mieć **adres URL pliku źródłowego** (jego kopii w repozytorium GitLab/GitHub): ustaw go w nowej sekcji Źródło w edytorze albo zaimportuj plik z adresu URL — zaimportowane pliki dostają odnośnik automatycznie i są od razu zsynchronizowane.
- Lista Plików ma nową kolumnę **Ostatnia synchronizacja** (z sortowaniem): brak źródła, nigdy nie zsynchronizowano albo jak dawno temu — ze znacznikiem „Zmiany lokalne", gdy kopia w bazie zmieniła się od tego czasu.
- **Synchronizuj z repozytorium** w menu Operacje wiersza pobiera kopię z repozytorium, pokazuje, po której stronie są zmiany (repozytorium, baza lub obie) oraz różnice linia po linii, a po Twoim potwierdzeniu nadpisuje kopię w bazie. Synchronizację w drugą stronę (baza → repozytorium) wykonujesz samodzielnie.
- Plik bez odnośnika do źródła pojawia się w raporcie błędów jako **Brak odnośnika do źródła** (z własną pigułką filtra).`,
  },
  {
    version: "1.10.0",
    date: "2026-08-30",
    en: `**Kind tiers** — every kind now shows its fill-in priority.

- The model is big; you won't fill everything at once. Tiers say where to start: **tier 1** — Domain, System, Group; **tier 2** — Component; **tier 3** — Resource, API; **tier 4** — User.
- A small numbered dot before every kind name marks its tier — on the kind pills, the Files, Hierarchy, Graph, Errors, and import views, the editor's Kind picker, and the registry pages. Hover it for a reminder.
- Purely visual: nothing about validation or saving changes — lower tiers first is a recommendation, not a rule.`,
    pl: `**Poziomy rodzajów** — każdy rodzaj pokazuje teraz swój priorytet uzupełniania.

- Model jest duży; nie uzupełnisz wszystkiego naraz. Poziomy podpowiadają, od czego zacząć: **poziom 1** — Domain, System, Group; **poziom 2** — Component; **poziom 3** — Resource, API; **poziom 4** — User.
- Mała numerowana kropka przed nazwą rodzaju oznacza jego poziom — na pigułkach rodzajów, w widokach Plików, Hierarchii, Grafu, Błędów i importu, w wyborze rodzaju w edytorze oraz na stronach rejestrów. Najedź na nią, by zobaczyć przypomnienie.
- Zmiana czysto wizualna: walidacja i zapisywanie działają bez zmian — „najpierw niższe poziomy" to zalecenie, nie reguła.`,
  },
  {
    version: "1.9.0",
    date: "2026-08-30",
    en: `**Lenses** — save your filters, name them, share them.

- A lens is a saved snapshot of the filter set (name, namespace, kind pills, type, lifecycle, owner, tag, label) — pick one from the new combo box next to the Filter button on the Hierarchy, Files, Graph, and Errors views, and the filters apply instantly.
- Lenses are shared between those views: save one on Hierarchy, apply it on Files or Graph.
- Each lens is **private** (only you see it) or **public** (everyone sees it, grouped and labeled in the picker; only its creator can change it).
- Overwrite a lens with your current filters, rename it, flip its visibility, or delete it from the actions menu; a "Modified" badge shows when your filters have drifted from the selected lens.`,
    pl: `**Soczewki** — zapisuj filtry, nazywaj je i udostępniaj.

- Soczewka to zapisany zestaw filtrów (nazwa, przestrzeń nazw, pigułki rodzajów, typ, cykl życia, właściciel/właścicielka, tag, etykieta) — wybierz ją z nowego pola wyboru obok przycisku Filtry w widokach Hierarchii, Plików, Grafu i Błędów, a filtry zastosują się od razu.
- Soczewki są wspólne dla tych widoków: zapisz na Hierarchii, zastosuj na Plikach lub Grafie.
- Każda soczewka jest **prywatna** (widzisz ją tylko Ty) lub **publiczna** (widzą ją wszyscy, pogrupowaną i oznaczoną w polu wyboru; zmieniać może ją tylko osoba, która ją utworzyła).
- Z menu akcji nadpiszesz soczewkę bieżącymi filtrami, zmienisz jej nazwę i widoczność albo ją usuniesz; plakietka „Zmieniona" pokazuje, że filtry odbiegły od wybranej soczewki.`,
  },
  {
    version: "1.8.0",
    date: "2026-08-30",
    en: `The Cross-check page is now **Errors** — and it catches everything.

- The page (now at /errors) reports every error class in your files: unresolved/wrong/self references, registry violations (labels, annotations, tags, types, lifecycles), and two new classes — files whose stored structure no longer passes validation, and files whose namespace was removed from the dictionary.
- The same filters as the Files and Hierarchy views (name, namespace, kind pills, type, lifecycle, owner, tag, label) narrow the report.
- New **Error types** pills let you show or hide whole error classes at a glance.`,
    pl: `Strona Weryfikacji to teraz **Błędy** — i wychwytuje wszystko.

- Strona (teraz pod /errors) raportuje każdą klasę błędów w plikach: nierozwiązane/błędne/własne odwołania, naruszenia rejestrów (etykiety, adnotacje, tagi, typy, cykle życia) oraz dwie nowe klasy — pliki, których zapisana struktura nie przechodzi już walidacji, i pliki, których przestrzeń nazw została usunięta ze słownika.
- Te same filtry co w widokach Plików i Hierarchii (nazwa, przestrzeń nazw, pigułki rodzajów, typ, cykl życia, właściciel/właścicielka, tag, etykieta) zawężają raport.
- Nowe pigułki **Rodzaje błędów** pozwalają jednym kliknięciem pokazać lub ukryć całe klasy błędów.`,
  },
  {
    version: "1.7.1",
    date: "2026-08-30",
    en: `Manual graph dragging is now fluent.

- Dragging a node in the Graph page's **Manual** mode no longer makes the canvas flicker or disappear mid-drag — nodes follow the pointer smoothly and stay visible the whole time.`,
    pl: `Ręczne przeciąganie w grafie jest teraz płynne.

- Przeciąganie węzła w trybie **Ręcznym** strony Grafu nie powoduje już migotania ani znikania obszaru roboczego w trakcie ruchu — węzły płynnie podążają za wskaźnikiem i pozostają cały czas widoczne.`,
  },
  {
    version: "1.7.0",
    date: "2026-08-30",
    en: `Arrange the graph your way.

- The Graph page now has two layout modes: **Auto** (the computed layout, as before) and **Manual** — drag nodes wherever you like and they stay put.
- Your arrangement is saved to your account, so it follows you across browsers and devices; **Reset layout** starts you over from the computed layout.`,
    pl: `Ułóż graf po swojemu.

- Strona Grafu ma teraz dwa tryby układu: **Automatyczny** (wyliczany układ, jak dotychczas) i **Ręczny** — przeciągnij węzły, gdzie chcesz, a zostaną na miejscu.
- Twój układ zapisuje się na koncie, więc podąża za Tobą między przeglądarkami i urządzeniami; **Resetuj układ** zaczyna od nowa od wyliczonego układu.`,
  },
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
