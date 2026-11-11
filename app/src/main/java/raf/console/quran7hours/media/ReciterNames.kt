package raf.console.quran7hours.media

/**
 * Русские имена чтецов для выпадающего списка в тафсире.
 * Названия GitHub-репозиториев и URL аудио не изменяются.
 * В списке отображаются только репозитории, найденные ReciterRepository.
 */
object ReciterNames {
    private val known: Map<String, String> = buildMap {
        fun add(name: String, vararg aliases: String) {
            aliases.forEach { put(it, name) }
        }

        add("Абдуль-Басит Абдус-Самад", "abdulsamad", "abdulbasit")
        add("Абдуль-Басит Абдус-Самад (муратталь)", "abdulbasitmurattal")
        add("Абдуль-Басит Абдус-Самад (муджаввад)", "abdulbasitmujawwad", "abdulbasitmujawad")
        add("Абдуллах Авад аль-Джухани", "abdullahaawwad", "abdullaah3awwaadaljuhaynee", "abdullahaawadaljuhani", "juhany")
        add("Абдуллах Басфар", "basfar", "abdullahbasfar")
        add("Абдуллах Матруд", "matroud", "abdullahmatroud")
        add("Абдуррахман ас-Судайс", "sudais", "abdurrahmaansudais", "abdurrahmaanasudais")
        add("Абу Бакр аш-Шатри", "shatri", "abubakrshatri", "abubakrashshaatree")
        add("Ахмад Нуайна", "ahmedneana", "neana")
        add("Ахмад ибн Али аль-Аджми", "ajamy", "ahmedajamy", "ahmedibnalialajamy")
        add("Акрам аль-Алакими", "alaqimy", "alaqimi", "akramalaqimy")
        add("Мишари Рашид аль-Афаси", "alafasy", "misharyalafasy", "misharyrashidalafasy")
        add("Али Хаджадж ас-Сувайси", "alihajjaj", "alihajjajalsuesy")
        add("Али Абдуллах Джабир", "alijaber")
        add("Айман Рушди Сувайд", "aymansowaid", "aymansuwaid")
        add("Фарис Аббад", "faresabbad")
        add("Саад аль-Гамиди", "ghamadi", "ghamdi", "saadghamdi", "saadalghamdi", "saadalghamdi")
        add("Хани ар-Рифаи", "hanirifai")
        add("Али Абдуррахман аль-Хузайфи", "hudhaify", "huthaify")
        add("Махмуд Халиль аль-Хусари", "husary")
        add("Махмуд Халиль аль-Хусари (муджаввад)", "husarymujawwad")
        add("Махмуд Халиль аль-Хусари (обучающее чтение)", "husarymuallim", "husaryteacher")
        add("Ибрахим аль-Ахдар", "ibrahimakhdar")
        add("Карим Мансури", "karimmansoori")
        add("Халид Абдуллах аль-Кахтани", "khalidalqahtani", "khalidqahtani", "khalidqahtany", "khaalidabdullaahalqahtaanee")
        add("Махир аль-Муайкли", "mahermuaiqly", "maheralmuaiqly")
        add("Мухаммад Сиддик аль-Миншави", "menshawi", "minshawy", "minshawi")
        add("Мухаммад Сиддик аль-Миншави (муратталь)", "minshawymurattal", "minshawimurattal")
        add("Мухаммад Сиддик аль-Миншави (муджаввад)", "minshawymujawwad", "minshawimujawwad", "minshawimujawad")
        add("Мухаммад Сиддик аль-Миншави (обучающее чтение)", "minshawyteacher")
        add("Мухаммад Махмуд ат-Таблави", "tablaway", "mohammadaltablaway")
        add("Мухаммад Абдуль-Карим", "abdulkareem", "abdulkarim", "muhammadabdulkareem")
        add("Мухаммад Айюб", "muhammadayyoub", "ayyoub")
        add("Мухаммад Джибриль", "muhammadjibreel", "jibreel")
        add("Мухсин аль-Касим", "muhsinalqasim", "muhsinqasim")
        add("Мустафа Исмаил", "mustafaismail")
        add("Насир аль-Катами", "nasseralqatami", "nasserqatami", "qatami")
        add("Шахрияр Пархизгар", "parhizgar")
        add("Сахль Ясин", "sahlyassin")
        add("Салах Абдуррахман Бухатир", "salaahabdulrahmanbukhatir", "bukhatir")
        add("Салах аль-Будайр", "salahalbudair", "budair")
        add("Сауд Ибрахим аш-Шурайм", "shuraym", "shuraim", "saoodashshuraym")
        add("Ясир Саляма", "yasersalamah")
        add("Ясир ад-Дусари", "dussary", "dossary", "aldossary", "dosari", "yasseraddussary", "yasserdossary")
        add("Азиз Алили", "azizalili")
        add("Халифа ат-Тунайджи", "khalefaaltunaiji", "khalefatunaiji", "khalifatunaiji", "khaleefatunaiji", "tunaiji")
        add("Махмуд Али аль-Банна", "mahmoudalibanna", "mahmoudbanna", "mahmoodbanna", "mahmoudalibannaa", "banna")
    }

    private fun normalize(value: String): String = value
        .removePrefix("q7h_").lowercase()
        .replace(Regex("[^a-z0-9]"), "")
        .replace(Regex("(?:32|40|48|64|96|128|192|256|320)(?:kbps|k)?$"), "")

    fun knownName(repository: String): String? = known[normalize(repository)]

    /** Never display English repository slugs as a reciter name. */
    fun displayName(repository: String): String =
        knownName(repository) ?: "Чтец (уточните имя в справочнике)"
}
