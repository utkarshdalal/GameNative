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
    def parse(value: str):
        """Parse a language string into a Language object"""
        # Simple implementation for Android compatibility
        # Default to English if parsing fails
        if isinstance(value, Language):
            return value
        
        # Map common language strings to codes
        lang_map = {
            "english": "en-US",
            "en": "en-US",
            "en-us": "en-US",
            "spanish": "es-ES",
            "es": "es-ES",
            "french": "fr-FR",
            "fr": "fr-FR",
            "german": "de-DE",
            "de": "de-DE",
            "italian": "it-IT",
            "it": "it-IT",
            "portuguese": "pt-BR",
            "pt": "pt-BR",
            "russian": "ru-RU",
            "ru": "ru-RU",
            "polish": "pl-PL",
            "pl": "pl-PL",
            "chinese": "zh-CN",
            "zh": "zh-CN",
            "japanese": "ja-JP",
            "ja": "ja-JP",
            "korean": "ko-KR",
            "ko": "ko-KR",
        }
        
        code = lang_map.get(value.lower(), value)
        
        return Language(
            code=code,
            name=value.capitalize(),
            native_name=value.capitalize(),
            deprecated_codes=[]
        )
