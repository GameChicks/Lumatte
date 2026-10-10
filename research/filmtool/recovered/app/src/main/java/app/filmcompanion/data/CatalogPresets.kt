package app.filmcompanion.data



/** Stable IDs let new catalog entries appear on existing installations without replacing user data. */

object CatalogPresets {

    private fun rows(text: String): List<List<String>> = text.trimIndent().lineSequence()

        .map(String::trim).filter(String::isNotEmpty)

        .map { line -> line.split('|', limit = 8).map(String::trim) }.toList()



    private fun id(prefix: String, vararg fields: String): String = prefix + fields.joinToString("-")

        .lowercase().replace(Regex("[^a-z0-9]+"), "-").trim('-')



    val cameras: List<CameraEntity> = rows("""

        Canon|FTb-N|Canon FD|135|canon_ftbn

        Canon|AE-1 Program|Canon FD|135|

        Canon|A-1|Canon FD|135|

        Canon|F-1 New|Canon FD|135|

        Nikon|F3|Nikon F|135|nikon_f3

        Nikon|FM2|Nikon F|135|

        Nikon|FE2|Nikon F|135|

        Nikon|F100|Nikon F|135|

        Leica|M3|Leica M|135|

        Leica|M6|Leica M|135|

        Leica|R6.2|Leica R|135|

        Olympus|OM-1|Olympus OM|135|

        Olympus|OM-2n|Olympus OM|135|

        Pentax|K1000|Pentax K|135|

        Pentax|LX|Pentax K|135|

        Pentax|67|Pentax 67|67|

        Minolta|X-700|Minolta SR|135|

        Minolta|SRT 101|Minolta SR|135|

        Fujifilm|GA645|Fixed|645|

        Fujifilm|GX680 III|Fujifilm GX|68|

        Fujifilm|GW690III|Fixed|69|

        Contax|G1|Contax G|135|

        Contax|G2|Contax G|135|

        Contax|645|Contax 645|645|

        Mamiya|645 Pro|Mamiya 645|645|

        Mamiya|RB67 Pro S|Mamiya RB67|67|

        Hasselblad|500C/M|Hasselblad V|66|

        Hasselblad|503CW|Hasselblad V|66|

        Bronica|ETRSi|Bronica ETR|645|

        Bronica|SQ-A|Bronica SQ|66|

        Ricoh|GR1v|Fixed|135|

        Ricoh|XR-7|Pentax K|135|

        Yashica|FX-3 Super 2000|Contax/Yashica|135|

        Yashica|Mat-124G|Fixed|66|

        Voigtlander|Bessa R2|Leica M|135|

        Voigtlander|Bessa R3A|Leica M|135|

        Konica|Autoreflex T3|Konica AR|135|

        Konica|Hexar RF|Leica M|135|

        Rollei|35 S|Fixed|135|

        Rollei|Rolleiflex 2.8F|Fixed|66|

        Graflex|Speed Graphic 4x5|Graflex Graphic|4x5|

    """).map { row ->

        val (brand, model, mount, format, image) = row

        CameraEntity(when (image) {

            "canon_ftbn" -> "canon-ftbn"

            else -> id("preset-camera-", brand, model)

        },

            brand, model, mount, format, image.ifBlank { null }, isPreset = true, createdAt = 0L)

    }



    private val originalLenses = listOf(

        LensEntity("nfd-50-14", "Canon", "New FD 50mm f/1.4", "Canon FD", 50, 50, 1.4, isPreset = true, createdAt = 0L),

        LensEntity("nfd-75-200-45", "Canon", "New FD 75–200mm f/4.5", "Canon FD", 75, 200, 4.5, isPreset = true, createdAt = 0L),

        LensEntity("fd-135-25-sc", "Canon", "FD 135mm f/2.5 S.C.", "Canon FD", 135, 135, 2.5, isPreset = true, createdAt = 0L)

    )



    val lenses: List<LensEntity> = originalLenses + rows("""

        Canon|Canon FD|FD 20mm f/2.8|20|20|2.8

        Canon|Canon FD|FD 24mm f/2.8|24|24|2.8

        Canon|Canon FD|FD 28mm f/2.8|28|28|2.8

        Canon|Canon FD|FD 35mm f/2|35|35|2.0

        Canon|Canon FD|FD 50mm f/1.8|50|50|1.8

        Canon|Canon FD|FD 85mm f/1.8|85|85|1.8

        Canon|Canon FD|FD 100mm f/2.8|100|100|2.8

        Canon|Canon FD|FD 200mm f/4|200|200|4.0

        Canon|Canon FD|New FD 35-105mm f/3.5|35|105|3.5

        Nikon|Nikon F|Nikkor 20mm f/2.8|20|20|2.8

        Nikon|Nikon F|Nikkor 24mm f/2.8|24|24|2.8

        Nikon|Nikon F|Nikkor 28mm f/2.8|28|28|2.8

        Nikon|Nikon F|Nikkor 35mm f/2|35|35|2.0

        Nikon|Nikon F|Nikkor 50mm f/1.4|50|50|1.4

        Nikon|Nikon F|Nikkor 50mm f/1.8|50|50|1.8

        Nikon|Nikon F|Micro-Nikkor 55mm f/2.8|55|55|2.8

        Nikon|Nikon F|Nikkor 85mm f/1.8|85|85|1.8

        Nikon|Nikon F|Nikkor 105mm f/2.5|105|105|2.5

        Nikon|Nikon F|Nikkor 135mm f/2.8|135|135|2.8

        Nikon|Nikon F|Nikkor 180mm f/2.8|180|180|2.8

        Nikon|Nikon F|Zoom-Nikkor 35-70mm f/3.5|35|70|3.5

        Leica|Leica M|Summicron-M 28mm f/2|28|28|2.0

        Leica|Leica M|Summicron-M 35mm f/2|35|35|2.0

        Leica|Leica M|Summicron-M 50mm f/2|50|50|2.0

        Leica|Leica M|Summicron-M 90mm f/2|90|90|2.0

        Leica|Leica M|Summilux-M 35mm f/1.4|35|35|1.4

        Leica|Leica M|Summilux-M 50mm f/1.4|50|50|1.4

        Leica|Leica M|Elmarit-M 28mm f/2.8|28|28|2.8

        Leica|Leica M|Elmarit-M 90mm f/2.8|90|90|2.8

        Olympus|Olympus OM|Zuiko 24mm f/2.8|24|24|2.8

        Olympus|Olympus OM|Zuiko 28mm f/2.8|28|28|2.8

        Olympus|Olympus OM|Zuiko 35mm f/2.8|35|35|2.8

        Olympus|Olympus OM|Zuiko 50mm f/1.4|50|50|1.4

        Olympus|Olympus OM|Zuiko 50mm f/1.8|50|50|1.8

        Olympus|Olympus OM|Zuiko 85mm f/2|85|85|2.0

        Olympus|Olympus OM|Zuiko 100mm f/2.8|100|100|2.8

        Olympus|Olympus OM|Zuiko 135mm f/3.5|135|135|3.5

        Pentax|Pentax K|SMC Pentax-M 28mm f/2.8|28|28|2.8

        Pentax|Pentax K|SMC Pentax-M 35mm f/2.8|35|35|2.8

        Pentax|Pentax K|SMC Pentax-M 50mm f/1.4|50|50|1.4

        Pentax|Pentax K|SMC Pentax-M 50mm f/1.7|50|50|1.7

        Pentax|Pentax K|SMC Pentax-M 100mm f/2.8|100|100|2.8

        Pentax|Pentax K|SMC Pentax-M 135mm f/3.5|135|135|3.5

        Pentax|Pentax K|SMC Pentax-A 35-70mm f/4|35|70|4.0

        Pentax|Pentax 67|SMC Pentax 67 45mm f/4|45|45|4.0

        Pentax|Pentax 67|SMC Pentax 67 55mm f/4|55|55|4.0

        Pentax|Pentax 67|SMC Takumar 105mm f/2.4|105|105|2.4

        Pentax|Pentax 67|SMC Pentax 67 165mm f/2.8|165|165|2.8

        Minolta|Minolta SR|MD Rokkor 28mm f/2.8|28|28|2.8

        Minolta|Minolta SR|MD Rokkor 35mm f/2.8|35|35|2.8

        Minolta|Minolta SR|MD Rokkor 45mm f/2|45|45|2.0

        Minolta|Minolta SR|MD Rokkor 50mm f/1.4|50|50|1.4

        Minolta|Minolta SR|MD Rokkor 50mm f/1.7|50|50|1.7

        Minolta|Minolta SR|MD Rokkor 85mm f/1.7|85|85|1.7

        Minolta|Minolta SR|MD Rokkor 100mm f/2.5|100|100|2.5

        Minolta|Minolta SR|MD Rokkor 135mm f/2.8|135|135|2.8

        Fujifilm|Fujifilm GX|Fujinon GX 65mm f/5.6|65|65|5.6

        Fujifilm|Fujifilm GX|Fujinon GX 80mm f/5.6|80|80|5.6

        Fujifilm|Fujifilm GX|Fujinon GX 100mm f/4|100|100|4.0

        Fujifilm|Fujifilm GX|Fujinon GX 135mm f/5.6|135|135|5.6

        Fujifilm|Fujifilm GX|Fujinon GX 150mm f/4.5|150|150|4.5

        Contax|Contax G|Carl Zeiss Biogon 28mm f/2.8 G|28|28|2.8

        Contax|Contax G|Carl Zeiss Planar 35mm f/2 G|35|35|2.0

        Contax|Contax G|Carl Zeiss Planar 45mm f/2 G|45|45|2.0

        Contax|Contax G|Carl Zeiss Sonnar 90mm f/2.8 G|90|90|2.8

        Contax|Contax/Yashica|Carl Zeiss Distagon 28mm f/2.8 C/Y|28|28|2.8

        Contax|Contax/Yashica|Carl Zeiss Planar 50mm f/1.4 C/Y|50|50|1.4

        Contax|Contax 645|Carl Zeiss Planar 80mm f/2 645|80|80|2.0

        Mamiya|Mamiya 645|Sekor C 45mm f/2.8|45|45|2.8

        Mamiya|Mamiya 645|Sekor C 55mm f/2.8|55|55|2.8

        Mamiya|Mamiya 645|Sekor C 80mm f/2.8|80|80|2.8

        Mamiya|Mamiya 645|Sekor C 110mm f/2.8|110|110|2.8

        Mamiya|Mamiya 645|Sekor C 150mm f/3.5|150|150|3.5

        Mamiya|Mamiya RB67|Sekor C 65mm f/4.5|65|65|4.5

        Mamiya|Mamiya RB67|Sekor C 90mm f/3.8|90|90|3.8

        Mamiya|Mamiya RB67|Sekor C 127mm f/3.8|127|127|3.8

        Mamiya|Mamiya RB67|Sekor C 180mm f/4.5|180|180|4.5

        Hasselblad|Hasselblad V|Zeiss Distagon 50mm f/4|50|50|4.0

        Hasselblad|Hasselblad V|Zeiss Distagon 60mm f/3.5|60|60|3.5

        Hasselblad|Hasselblad V|Zeiss Planar 80mm f/2.8|80|80|2.8

        Hasselblad|Hasselblad V|Zeiss Makro-Planar 120mm f/4|120|120|4.0

        Hasselblad|Hasselblad V|Zeiss Sonnar 150mm f/4|150|150|4.0

        Hasselblad|Hasselblad V|Zeiss Sonnar 250mm f/5.6|250|250|5.6

        Bronica|Bronica ETR|Zenzanon PE 40mm f/4|40|40|4.0

        Bronica|Bronica ETR|Zenzanon PE 50mm f/2.8|50|50|2.8

        Bronica|Bronica ETR|Zenzanon PE 75mm f/2.8|75|75|2.8

        Bronica|Bronica ETR|Zenzanon PE 100mm f/4 Macro|100|100|4.0

        Bronica|Bronica ETR|Zenzanon PE 150mm f/3.5|150|150|3.5

        Ricoh|Pentax K|XR Rikenon 28mm f/2.8|28|28|2.8

        Ricoh|Pentax K|XR Rikenon 35mm f/2.8|35|35|2.8

        Ricoh|Pentax K|XR Rikenon 50mm f/1.7|50|50|1.7

        Ricoh|Pentax K|XR Rikenon 135mm f/2.8|135|135|2.8

        Yashica|Contax/Yashica|Yashica ML 28mm f/2.8|28|28|2.8

        Yashica|Contax/Yashica|Yashica ML 35mm f/2.8|35|35|2.8

        Yashica|Contax/Yashica|Yashica ML 50mm f/1.7|50|50|1.7

        Yashica|Contax/Yashica|Yashica ML 135mm f/2.8|135|135|2.8

        Voigtlander|Leica M|Color-Skopar 21mm f/4|21|21|4.0

        Voigtlander|Leica M|Color-Skopar 35mm f/2.5|35|35|2.5

        Voigtlander|Leica M|Nokton Classic 35mm f/1.4|35|35|1.4

        Voigtlander|Leica M|Nokton Classic 40mm f/1.4|40|40|1.4

        Voigtlander|Leica M|Nokton 50mm f/1.5|50|50|1.5

        Konica|Konica AR|Hexanon AR 28mm f/3.5|28|28|3.5

        Konica|Konica AR|Hexanon AR 35mm f/2.8|35|35|2.8

        Konica|Konica AR|Hexanon AR 40mm f/1.8|40|40|1.8

        Konica|Konica AR|Hexanon AR 50mm f/1.7|50|50|1.7

        Konica|Konica AR|Hexanon AR 57mm f/1.4|57|57|1.4

        Rollei|Rollei QBM|Rolleinar MC 28mm f/2.8|28|28|2.8

        Rollei|Rollei QBM|Planar 50mm f/1.8|50|50|1.8

        Rollei|Rollei QBM|Planar 50mm f/1.4|50|50|1.4

        Rollei|Rollei QBM|Sonnar 85mm f/2.8|85|85|2.8

    """).map { row ->

        val brand = row[0]; val mount = row[1]; val model = row[2]

        val focalMin = row[3]; val focalMax = row[4]; val aperture = row[5]

        LensEntity(id("preset-lens-", brand, mount, model), brand, model, mount,

            focalMin.toInt(), focalMax.toInt(), aperture.toDouble(), isPreset = true, createdAt = 0L)

    }



    val filmStocks: List<FilmStockEntity> = rows("""

        kodak-gold-200|Kodak|Gold 200|200|COLOR_NEGATIVE|C-41

        kodak-ultramax-400|Kodak|UltraMax 400|400|COLOR_NEGATIVE|C-41

        kodak-colorplus-200|Kodak|ColorPlus 200|200|COLOR_NEGATIVE|C-41

        kodak-ektar-100|Kodak|Ektar 100|100|COLOR_NEGATIVE|C-41

        fujifilm-velvia-50|Fujifilm|Velvia 50|50|COLOR_REVERSAL|E-6

        cinestill-800t|CineStill|800T|800|COLOR_NEGATIVE|C-41

        cinestill-400d|CineStill|400D|400|COLOR_NEGATIVE|C-41

        cinestill-50d|CineStill|50D|50|COLOR_NEGATIVE|C-41

        kodak-5203|Kodak|VISION3 5203 50D|50|COLOR_NEGATIVE|ECN-2

        kodak-5207|Kodak|VISION3 5207 250D|250|COLOR_NEGATIVE|ECN-2

        kodak-5213|Kodak|VISION3 5213 200T|200|COLOR_NEGATIVE|ECN-2

        kodak-5219|Kodak|VISION3 5219 500T|500|COLOR_NEGATIVE|ECN-2

        kodak-5260|Kodak|VISION2 5260 500T|500|COLOR_NEGATIVE|ECN-2

        fuji-8573|Fujifilm|ETERNA 500T 8573|500|COLOR_NEGATIVE|ECN-2

        kodak-5222|Kodak|Double-X 5222|250|BLACK_WHITE|D-96

        ilford-hp5|Ilford|HP5 Plus|400|BLACK_WHITE|B&W

        ilford-pan400|Ilford|Pan 400|400|BLACK_WHITE|B&W

        ilford-delta100|Ilford|Delta 100|100|BLACK_WHITE|B&W

        ilford-delta400|Ilford|Delta 400|400|BLACK_WHITE|B&W

        ilford-fp4|Ilford|FP4 Plus|125|BLACK_WHITE|B&W

        kodak-tmax100|Kodak|T-MAX 100|100|BLACK_WHITE|B&W

        kodak-tmax400|Kodak|T-MAX 400|400|BLACK_WHITE|B&W

        kodak-trix400|Kodak|Tri-X 400|400|BLACK_WHITE|B&W

        foma-fomapan100|Foma|FOMAPAN 100|100|BLACK_WHITE|B&W

        foma-fomapan400|Foma|FOMAPAN 400|400|BLACK_WHITE|B&W

        rollei-rpx100|Rollei|RPX 100|100|BLACK_WHITE|B&W

        shanghai-gp3|Shanghai|GP3 100|100|BLACK_WHITE|B&W

        lucky-shd400|Lucky|SHD 400|400|BLACK_WHITE|B&W

        lucky-shd100|Lucky|SHD 100|100|BLACK_WHITE|B&W

        fuji-c200|Fujifilm|C200|200|COLOR_NEGATIVE|C-41

        fuji-industrial400|Fujifilm|Industrial 400|400|COLOR_NEGATIVE|C-41

        fuji-premium400|Fujifilm|Premium 400|400|COLOR_NEGATIVE|C-41

        fuji-proimage100|Fujifilm|ProImage 100（型号待核验）|100|COLOR_NEGATIVE|C-41

        fuji-superia400|Fujifilm|SUPERIA X-TRA 400|400|COLOR_NEGATIVE|C-41

        kodak-proimage100|Kodak|ProImage 100|100|COLOR_NEGATIVE|C-41

        lucky-c200|Lucky|C200|200|COLOR_NEGATIVE|C-41

        yesstar-200|YesStar|200|200|COLOR_NEGATIVE|C-41

        fuji-provia100f|Fujifilm|Provia 100F|100|COLOR_REVERSAL|E-6

        fuji-velvia100|Fujifilm|Velvia 100|100|COLOR_REVERSAL|E-6

        fuji-astia100f|Fujifilm|Astia 100F|100|COLOR_REVERSAL|E-6

        kodak-e100|Kodak|Ektachrome E100|100|COLOR_REVERSAL|E-6

    """).map { row ->

        val key = row[0]; val brand = row[1]; val model = row[2]

        val iso = row[3]; val type = row[4]; val process = row[5]

        FilmStockEntity(key, brand, model, iso.toInt(), type, process, isPreset = true, createdAt = 0L)

    }

}
