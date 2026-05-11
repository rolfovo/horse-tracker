# Zadani aplikace: Horse Tracker

Tento dokument popisuje, co ma aplikace delat z pohledu zadani produktu.

Neni to technicky navod na implementaci. Neobsahuje postup programovani, architekturu ani kodove detaily. Slouzi jako podklad pro vyvoj, zadani pro programatora nebo jako funkcni specifikace.

## 1. Nazev a ucel aplikace

Nazev aplikace:

- Horse Tracker

Hlavni ucel:

- zaznamenavat jizdy na koni
- prirazovat jizdy ke konkretnim konim
- zobrazovat trasu jizdy na mape
- umoznit pozdejsi nacteni a nasledovani drive ulozene trasy
- uchovavat a zalohovat data tak, aby se o ne uzivatel po reinstalaci nepripravil

## 2. Cilovy uzivatel

Cilovy uzivatel je clovek, ktery:

- jezdi na koni v terenu nebo na vyjizdkach
- chce si uchovavat historii jizd
- potrebuje mit jizdy rozdelene podle jednotlivych koni
- muze chtit opakovane projet stejnou trasu
- chce mit aplikaci jednoduchou, citelnou a pouzitelnou i venku na mobilu

## 3. Hlavni princip aplikace

Aplikace ma fungovat jako specializovany tracker jizd na koni.

Zakladni logika:

1. uzivatel si vybere kone
2. spusti zaznam jizdy
3. aplikace uklada polohu a zobrazuje trasu na mape
4. uzivatel muze pridavat body zajmu nebo poznamky
5. po skonceni jizdy trasu ulozi
6. pozdeji si ji muze znovu otevrit, exportovat nebo podle ni jet v rezimu follow

## 4. Povinne funkcni oblasti

### 4.1 Sprava koni

Aplikace musi umet:

- vytvorit noveho kone
- zobrazit seznam vsech koni
- vybrat aktivniho kone
- odstranit kone
- pri smazani kone odstranit i jeho ulozene jizdy

Pozadavky na chovani:

- pri prvnim spusteni aplikace musi byt mozne kone vybrat nebo zalozit
- vybrany kun musi byt jasne viditelny
- zmena kone musi byt jednoducha a rychla

### 4.2 Zaznam jizdy

Aplikace musi umet:

- spustit novy zaznam jizdy
- prubezne prijimat polohu telefonu
- vytvaret z polohy trasu
- zobrazovat aktualni prubeh jizdy na mape
- zastavit zaznam
- ulozit aktualni jizdu

Pozadavky na chovani:

- nova jizda musi vzdy zacinat cista, bez bodu z predchozi jizdy
- pri aktivnim zaznamu musi byt zrejme, ze recording bezi
- aplikace musi byt pouzitelna i behem pohybu venku

### 4.3 Waypointy a poznamky

Aplikace musi umet:

- pridat bod zajmu do aktualni jizdy
- pridat bod zajmu s textovou poznamkou
- pridat bod zajmu i pomoci hlasoveho vstupu

Pozadavky na chovani:

- waypoint musi byt svazany s aktualni jizdou
- waypoint musi byt viditelny na mape
- waypoint se musi ulozit spolu s trasou

### 4.4 Mapa

Aplikace musi obsahovat mapu, na ktere je videt:

- aktualni pozice uzivatele
- zaznamenana trasa aktualni jizdy
- waypointy
- volitelne i nasledovana trasa

Pozadavky na chovani:

- mapa musi byt dobre citelna venku
- mapa musi byt dostupna i kdyz zatim neni nactena aktualni poloha
- aplikace ma jasne signalizovat, jestli ceka na polohu
- mapa ma mit dostatecne velkou zobrazovanou plochu

### 4.5 Statistiky aktualni jizdy

Aplikace musi behem jizdy zobrazovat:

- ujetou vzdalenost
- aktualni rychlost
- prumernou rychlost
- dobu trvani jizdy
- presnost GPS, pokud je dostupna

Pozadavky na chovani:

- hodnoty se maji prubezne obnovovat
- format ma byt snadno citelny
- zobrazeni nesmi pretekat mimo obrazovku

### 4.6 Ukladani jizd

Aplikace musi umet:

- ulozit aktualni jizdu
- uchovat ji v seznamu jizd
- spojit ji s konkretnim konem
- pozdeji ji znovu nacist

Pozadavky na chovani:

- ulozena jizda musi obsahovat trasu i waypointy
- ulozeni nesmi omylem duplikovat predchozi data
- po ulozeni se jizda musi objevit v knihovne jizd

### 4.7 Seznam jizd

Aplikace musi umet:

- zobrazit seznam ulozenych jizd
- filtrovat jizdy podle kone
- nacist jizdu
- exportovat jizdu
- smazat jizdu

Pozadavky na chovani:

- seznam ma byt prehledny
- u kazde jizdy ma byt videt alespon datum, kun a vzdalenost
- mazani ma byt potvrzene

### 4.8 Follow rezim

Aplikace musi umet:

- nacist drive ulozenou trasu
- zobrazit ji na mape jako trasu pro nasledovani
- spustit rezim follow
- zastavit rezim follow
- otocit smer trasy

Pozadavky na chovani:

- pri nacteni follow trasy se nesmi smichat data z prave zaznamenavane jizdy
- uzivatel musi mit jasne videt, jestli aplikace jen zobrazuje trasu nebo aktivne jede ve follow rezimu
- po ukonceni follow musi byt moznost nasledovanou jizdu ulozit

### 4.9 Upozorneni mimo trasu

V rezimu follow ma aplikace umet:

- rozpoznat, ze se uzivatel vzdaluje od trasy
- zobrazit nebo hlasit odchylku od trasy
- rozpoznat navrat na trasu
- umoznit nastavit citlivost upozorneni

Pozadavky na chovani:

- uzivatel musi mit moznost nastavit hranici pro upozorneni
- uzivatel musi mit moznost nastavit hranici pro navrat na trasu
- aplikace nema hlasit zbytecne casto pri malych odchylkach

### 4.10 Hlasove funkce

Aplikace muze pri jizde pouzivat hlasove vystupy.

Pozadovane hlasove funkce:

- hlasove waypointy
- upozorneni na vyjeti z trasy
- hlaseni navratu na trasu
- hlaseni dojezdu do cile

Pozadavky na chovani:

- hlasove vystupy nesmi byt povinne pro zakladni pouziti aplikace
- kdyz hlasova funkce neni dostupna, aplikace se nesmi rozbit

### 4.11 Offline mapa okoli

Aplikace ma umet:

- stahnout mapove podklady okoli aktualni polohy
- pouzit je pozdeji bez internetu

Pozadavky na chovani:

- uzivatel musi mit jednoduchou volbu rozsahu stahovani
- aplikace ma jasne informovat, kdy stahovani probehlo

### 4.12 Export jedne jizdy

Aplikace musi umet:

- vyexportovat jednu ulozenou jizdu do souboru
- umoznit ulozit tento soubor treba do Google Drive

Pozadavky na chovani:

- export ma byt rucni a pod kontrolou uzivatele
- uzivatel ma moci vybrat cilove umisteni

### 4.13 Zalohovani cele aplikace

Aplikace musi umet:

- exportovat zalohu vsech dulezitych dat
- importovat zalohu zpet do aplikace

Zaloha musi obsahovat:

- seznam koni
- ulozene jizdy
- zakladni nastaveni aplikace souvisejici s trasami

Pozadavky na chovani:

- import ma obnovit data po reinstalaci aplikace
- aplikace musi uzivatele upozornit, ze import prepise stavajici data
- backup a restore nesmi bezet uprostred aktivniho recording nebo follow rezimu

### 4.14 Verze aplikace

Aplikace ma zobrazovat:

- cislo verze nainstalovane aplikace

Pozadavky na chovani:

- verze musi byt snadno dohledatelna primo v aplikaci
- uzivatel ma poznat, jestli ma opravdu nainstalovanou novou verzi

## 5. Pozadavky na uzivatelske rozhrani

Rozhrani ma byt:

- jednoduche
- citelne na mobilu venku
- navrzene primarne pro rychle pouziti jednou rukou
- vizualne sjednocene
- bez zbytecneho preplneni

Hlavni obrazovka ma obsahovat:

- informace o vybranem koni
- velkou mapu
- prehled aktivni jizdy
- hlavni ovladaci prvky
- pristup ke knihovne jizd
- pristup ke sprave koni a zaloham

Pozadavky na vzhled:

- texty se nesmi usekavat nebo pretekat mimo viditelnou oblast
- nejdulezitejsi akce musi byt rychle dostupne
- stav aplikace musi byt citelny na prvni pohled

## 6. Pozadavky na chovani pri poloze

Aplikace ma byt schopna:

- ziskat polohu i pri horsim prvnim fixu
- fungovat pokud je dostupna jen nektera forma polohy
- dat uzivateli srozumitelnou informaci, kdyz telefon polohu stale neposkytuje

Dulezite rozliseni:

- problem muze byt v prostredi
- problem muze byt v nastaveni telefonu
- problem muze byt v aplikaci

Aplikace proto ma:

- jasne ukazovat, ze ceka na polohu
- nepusobit dojmem zamrznuti
- pokud mozno ziskat aspon posledni znamou polohu

## 7. Pozadavky na beh na pozadi

Aplikace ma umet:

- pokracovat v zaznamu i pri zhasnutem displeji
- pokracovat v zaznamu i po odchodu z aplikace

Pozadavky na chovani:

- recording ma bezet jako foreground service
- uzivatel musi byt informovan, ze zaznam bezi
- aplikace ma pozadovat potrebna opravneni

## 8. Pozadavky na spolehlivost dat

Aplikace nesmi:

- slepovat novou jizdu s predchozi
- ponechat starou stopu pri nacteni follow trasy
- omylem ulozit spatnou nebo duplicitni jizdu
- po importu backupu ztratit vazbu mezi konmi a jizdami

Aplikace musi:

- pri kazde nove jizde zacit v cistem stavu
- pri nacteni jizdy korektne oddelit recording a follow data
- zachovat data i po restartu aplikace

## 9. Pozadavky na kompatibilitu

Aplikace ma byt navrzena pro:

- Android telefon
- realne venkovni pouziti
- ruzne kvality GPS

Je treba pocitat s tim, ze:

- ruzni vyrobci telefonu se chovaji odlisne
- nektere telefony maji agresivni uspory baterie
- polohove sluzby se nemusi chovat stejne na vsech zarizenich

## 10. Ne-funkcni pozadavky

Aplikace ma byt:

- stabilni
- predvidatelna
- snadno pochopitelna
- rozumne rychla
- pouzitelna i bez mobilnich dat po predchozim stazeni map

Pouziti ma byt:

- bez registrace
- bez povinneho cloudu
- bez slozite konfigurace

## 11. Minimalni scenare pouziti

### Scenar A: Prvni jizda

1. uzivatel spusti aplikaci
2. prida kone
3. vybere kone
4. povoli polohu
5. spusti recording
6. jede
7. prida waypoint
8. zastavi recording
9. ulozi jizdu

### Scenar B: Opakovani stare trasy

1. uzivatel otevre seznam jizd
2. nacte starsi jizdu
3. spusti follow
4. jede podle trasy
5. pri odchylce dostane upozorneni
6. ukonci follow

### Scenar C: Zaloha pred reinstalaci

1. uzivatel vytvori backup
2. ulozi ho na Google Drive
3. po reinstalaci aplikace backup importuje
4. kone i jizdy jsou zpet

## 12. Co neni hlavni cil aplikace

Neni nutne, aby aplikace:

- byla socialni sit
- sdilela jizdy verejne
- mela online ucet
- mela slozitou sportovni analytiku
- zavisela na stale internetovem pripojeni

## 13. Shrnuti zadani

Vysledna aplikace ma byt jednoduchy a spolehlivy mobilni tracker pro jizdy na koni, ktery:

- pracuje s vyberem kone
- umi zaznamenat trasu
- umi ji ulozit a znovu nacist
- umi podle ni jet v follow rezimu
- podporuje waypointy a hlasova upozorneni
- zobrazuje vse prehledne na mape
- umoznuje export a zalohu dat
- pomaha uzivateli neprejit o data po reinstalaci
