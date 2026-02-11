#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
Generate complete Korean strings.xml with all translations
"""

import re
import sys
from korean_translations_dict import KOREAN_TRANSLATIONS

def load_existing_korean():
    """Load existing Korean translations and source for comparison"""
    # Read source file
    with open('app/src/main/res/values/strings.xml', 'r', encoding='utf-8') as f:
        source_lines = f.readlines()
    
    source_dict = {}
    for line in source_lines:
        match = re.search(r'<string name="([^"]+)"(?:[^>]*)>(.+?)</string>', line)
        if match and 'translatable="false"' not in line:
            source_dict[match.group(1)] = match.group(2)
    
    # Read existing Korean
    korean_dict = {}
    try:
        with open('app/src/main/res/values-ko/strings.xml', 'r', encoding='utf-8') as f:
            for line in f:
                match = re.search(r'<string name="([^"]+)"(?:[^>]*)>(.+?)</string>', line)
                if match:
                    korean_dict[match.group(1)] = match.group(2)
    except FileNotFoundError:
        pass
    
    # Filter: only keep Korean translations that are actually different from English
    good_korean = {}
    for name, ko_text in korean_dict.items():
        if name in source_dict:
            eng_text = source_dict[name]
            eng_norm = eng_text.replace('&amp;', '&').replace('\\\\', '')
            ko_norm = ko_text.replace('&amp;', '&').replace('\\\\', '')
            if eng_norm != ko_norm:
                # This is a real translation
                good_korean[name] = ko_text
    
    return good_korean

def generate_complete_korean_strings():
    """Generate complete Korean strings.xml file"""
    
    # Read source English file
    with open('app/src/main/res/values/strings.xml', 'r', encoding='utf-8') as f:
        source_lines = f.readlines()
    
    # Load existing GOOD Korean translations (ones that are different from English)
    existing_good_korean = load_existing_korean()
    
    # Merge: new translations first, then good existing Korean overwrites
    # This way we use new translations for previously untranslated, but keep good existing ones
    all_translations = {**KOREAN_TRANSLATIONS, **existing_good_korean}
    
    output_lines = []
    translated_count = 0
    untranslated_count = 0
    
    for line in source_lines:
        # Check if this is a translatable string line
        match = re.search(r'<string name="([^"]+)"([^>]*)>(.+?)</string>', line)
        
        if match and 'translatable="false"' not in line:
            string_name = match.group(1)
            attributes = match.group(2)
            english_text = match.group(3)
            
            # Get Korean translation
            if string_name in all_translations:
                korean_text = all_translations[string_name]
                # Reconstruct the line with Korean translation
                indent = line[:len(line) - len(line.lstrip())]
                new_line = f'{indent}<string name="{string_name}"{attributes}>{korean_text}</string>\n'
                output_lines.append(new_line)
                translated_count += 1
            else:
                # No translation available, keep English (shouldn't happen)
                output_lines.append(line)
                untranslated_count += 1
                print(f"WARNING: No Korean translation for: {string_name}")
        else:
            # Not a translatable string or comment/other line, keep as is
            output_lines.append(line)
    
    # Write output file
    output_path = 'app/src/main/res/values-ko/strings.xml'
    with open(output_path, 'w', encoding='utf-8') as f:
        f.writelines(output_lines)
    
    print(f"\n=== Generation Complete ===")
    print(f"Loaded {len(existing_good_korean)} existing good Korean translations")
    print(f"Added {len(KOREAN_TRANSLATIONS)} new Korean translations")
    print(f"Total unique translations: {len(all_translations)}")
    print(f"")
    print(f"Output file lines: {len(output_lines)}")
    print(f"Translated strings: {translated_count}")
    print(f"Untranslated strings: {untranslated_count}")
    print(f"Output file: {output_path}")
    
    return len(output_lines), translated_count, untranslated_count

if __name__ == '__main__':
    line_count, translated, untranslated = generate_complete_korean_strings()
    
    if untranslated > 0:
        print(f"\nERROR: {untranslated} strings still untranslated!")
        sys.exit(1)
    elif line_count != 1020:
        print(f"\nERROR: Expected 1020 lines, got {line_count}")
        sys.exit(1)
    else:
        print("\nSUCCESS: All strings translated, file has correct line count!")
        sys.exit(0)

