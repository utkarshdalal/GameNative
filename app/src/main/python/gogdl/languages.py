from dataclasses import dataclass


@dataclass
class Language:
    code: str
    name: str
    native_name: str
    deprecated_codes: list[str]

    def __eq__(self, value: object) -> bool:
        # Compare the class by language code
        if isinstance(value, Language):
            return self.code == value.code
        # If comparing to string, look for the code, name and deprecated code
        if type(value) is str:
            return (
                value == self.code
                or value.lower() == self.name.lower()
                or value in self.deprecated_codes
            )
        return NotImplemented

    def __hash__(self):
        return hash(self.code)

    def __repr__(self):
        return self.code

    @staticmethod
    def parse(val: str):
        for lang in LANGUAGES:
            if lang == val:
                return lang


# Auto-generated list of languages
LANGUAGES = [
    Language("af-ZA", "Afrikaans", "Afrikaans", []),
    Language("ar", "Arabic", "العربية", []),
    Language("az-AZ", "Azeri", "Azərbaycan­ılı", []),
    Language("be-BY", "Belarusian", "Беларускі", ["be"]),
    Language("bn-BD", "Bengali", "বাংলা", ["bn_BD"]),
    Language("bg-BG", "Bulgarian", "български", ["bg", "bl"]),
    Language("bs-BA", "Bosnian", "босански", []),
    Language("ca-ES", "Catalan", "Català", ["ca"]),
    Language("cs-CZ", "Czech", "Čeština", ["cz"]),
    Language("cy-GB", "Welsh", "Cymraeg", []),
    Language("da-DK", "Danish", "Dansk", ["da"]),
    Language("de-DE", "German", "Deutsch", ["de"]),
    Language("dv-MV", "Divehi", "ދިވެހިބަސް", []),
    Language("el-GR", "Greek", "ελληνικά", ["gk", "el-GK"]),
    Language("en-GB", "British English", "British English", ["en_GB"]),
    Language("en-US", "English", "English", ["en"]),
    Language("es-ES", "Spanish", "Español", ["es"]),
    Language("es-MX", "Latin American Spanish", "Español (AL)", ["es_mx"]),
    Language("et-EE", "Estonian", "Eesti", ["et"]),
    Language("eu-ES", "Basque", "Euskara", []),
    Language("fa-IR", "Persian", "فارسى", ["fa"]),
    Language("fi-FI", "Finnish", "Suomi", ["fi"]),
    Language("fo-FO", "Faroese", "Føroyskt", []),
    Language("fr-FR", "French", "Français", ["fr"]),
    Language("gl-ES", "Galician", "Galego", []),
    Language("gu-IN", "Gujarati", "ગુજરાતી", ["gu"]),
    Language("he-IL", "Hebrew", "עברית", ["he"]),
    Language("hi-IN", "Hindi", "हिंदी", ["hi"]),
    Language("hr-HR", "Croatian", "Hrvatski", []),
    Language("hu-HU", "Hungarian", "Magyar", ["hu"]),
    Language("hy-AM", "Armenian", "Հայերեն", []),
    Language("id-ID", "Indonesian", "Bahasa Indonesia", []),
    Language("is-IS", "Icelandic", "Íslenska", ["is"]),
    Language("it-IT", "Italian", "Italiano", ["it"]),
    Language("ja-JP", "Japanese", "日本語", ["jp"]),
    Language("jv-ID", "Javanese", "ꦧꦱꦗꦮ", ["jv"]),
    Language("ka-GE", "Georgian", "ქართული", []),
    Language("kk-KZ", "Kazakh", "Қазақ", []),
    Language("kn-IN", "Kannada", "ಕನ್ನಡ", []),
    Language("ko-KR", "Korean", "한국어", ["ko"]),
    Language("kok-IN", "Konkani", "कोंकणी", []),
    Language("ky-KG", "Kyrgyz", "Кыргыз", []),
    Language("la", "Latin", "latine", []),
    Language("lt-LT", "Lithuanian", "Lietuvių", []),
    Language("lv-LV", "Latvian", "Latviešu", []),
    Language("ml-IN", "Malayalam", "മലയാളം", ["ml"]),
    Language("mi-NZ", "Maori", "Reo Māori", []),
    Language("mk-MK", "Macedonian", "Mакедонски јазик", []),
    Language("mn-MN", "Mongolian", "Монгол хэл", []),
    Language("mr-IN", "Marathi", "मराठी", ["mr"]),
    Language("ms-MY", "Malay", "Bahasa Malaysia", []),
    Language("mt-MT", "Maltese", "Malti", []),
    Language("nb-NO", "Norwegian", "Norsk", ["no"]),
    Language("nl-NL", "Dutch", "Nederlands", ["nl"]),
    Language("ns-ZA", "Northern Sotho", "Sesotho sa Leboa", []),
    Language("pa-IN", "Punjabi", "ਪੰਜਾਬੀ", []),
    Language("pl-PL", "Polish", "Polski", ["pl"]),
    Language("ps-AR", "Pashto", "پښتو", []),
    Language("pt-BR", "Portuguese (Brazilian)", "Português do Brasil", ["br"]),
    Language("pt-PT", "Portuguese", "Português", ["pt"]),
    Language("ro-RO", "Romanian", "Română", ["ro"]),
    Language("ru-RU", "Russian", "Pусский", ["ru"]),
    Language("sa-IN", "Sanskrit", "संस्कृत", []),
    Language("sk-SK", "Slovak", "Slovenčina", ["sk"]),
    Language("sl-SI", "Slovenian", "Slovenski", []),
    Language("sq-AL", "Albanian", "Shqipe", []),
    Language("sr-SP", "Serbian", "Srpski", ["sb"]),
    Language("sv-SE", "Swedish", "Svenska", ["sv"]),
    Language("sw-KE", "Kiswahili", "Kiswahili", []),
    Language("ta-IN", "Tamil", "தமிழ்", ["ta_IN"]),
    Language("te-IN", "Telugu", "తెలుగు", ["te"]),
    Language("th-TH", "Thai", "ไทย", ["th"]),
    Language("tl-PH", "Tagalog", "Filipino", []),
    Language("tn-ZA", "Setswana", "Setswana", []),
    Language("tr-TR", "Turkish", "Türkçe", ["tr"]),
    Language("tt-RU", "Tatar", "Татар", []),
    Language("uk-UA", "Ukrainian", "Українська", ["uk"]),
    Language("ur-PK", "Urdu", "اُردو", ["ur_PK"]),
    Language("uz-UZ", "Uzbek", "U'zbek", []),
    Language("vi-VN", "Vietnamese", "Tiếng Việt", ["vi"]),
    Language("xh-ZA", "isiXhosa", "isiXhosa", []),
    Language("zh-Hans", "Chinese (Simplified)", "中文(简体)", ["zh_Hans", "zh", "cn"]),
    Language("zh-Hant", "Chinese (Traditional)", "中文(繁體)", ["zh_Hant"]),
    Language("zu-ZA", "isiZulu", "isiZulu", []),
]
