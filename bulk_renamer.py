# bulk_renamer.py
#!/usr/bin/env python3
# -*- coding: utf-8 -*-

import sys
import os
import re
import argparse
import datetime
from pathlib import Path
import shutil

# ANSI colors
COLORS = {
    'green': '\033[92m',
    'red': '\033[91m',
    'yellow': '\033[93m',
    'blue': '\033[94m',
    'reset': '\033[0m'
}

def colorize(text, color):
    return f"{COLORS.get(color, '')}{text}{COLORS['reset']}"

def get_files(path, recursive, extensions, filter_regex):
    """Собирает список файлов по заданным условиям."""
    path = Path(path)
    if not path.exists():
        raise FileNotFoundError(f"Path not found: {path}")

    pattern = "*"
    if extensions:
        exts = [ext.strip() for ext in extensions.split(',')]
        pattern = "*." + ",*.".join(exts)

    files = []
    if recursive:
        for root, dirs, filenames in os.walk(path):
            for f in filenames:
                full = Path(root) / f
                if extensions:
                    if full.suffix.lower()[1:] not in exts:
                        continue
                if filter_regex and not re.search(filter_regex, f):
                    continue
                files.append(full)
    else:
        for f in path.glob(pattern):
            if filter_regex and not re.search(filter_regex, f.name):
                continue
            files.append(f)
    return files

def apply_template(template, old_name, ext, num, prefix, suffix, replace_from, replace_to, case):
    """Применяет шаблон и опции для генерации нового имени."""
    name_without_ext = old_name
    if ext:
        name_without_ext = old_name[:-len(ext)-1] if old_name.endswith('.'+ext) else old_name

    # Замена
    new_name = name_without_ext
    if replace_from is not None:
        new_name = re.sub(replace_from, replace_to, new_name)

    # Регистр
    if case == 'lower':
        new_name = new_name.lower()
    elif case == 'upper':
        new_name = new_name.upper()
    elif case == 'title':
        new_name = new_name.title()

    # Префикс/суффикс
    if prefix:
        new_name = prefix + new_name
    if suffix:
        new_name = new_name + suffix

    # Номер
    if num is not None:
        # Подстановка {num} в шаблоне
        pass  # Обрабатываем отдельно

    # Заполняем шаблон
    vars = {
        'name': new_name,
        'ext': ext or '',
        'old': old_name,
        'date': datetime.datetime.now().strftime('%Y-%m-%d'),
        'prefix': prefix or '',
        'suffix': suffix or '',
        'num': num or 0
    }
    # Пользовательские переменные из шаблона
    # Ищем {var} и заменяем
    def repl(match):
        key = match.group(1)
        if key == 'num':
            # Поддержка форматирования: {num:03d}
            if ':' in key:
                fmt = key.split(':',1)[1]
                return format(vars['num'], fmt)
            return str(vars['num'])
        return str(vars.get(key, match.group(0)))
    result = re.sub(r'\{([^{}]+)\}', repl, template)
    return result

def main():
    parser = argparse.ArgumentParser(description="BulkRenamer – массовое переименование файлов")
    parser.add_argument('template', help='Шаблон нового имени с переменными {name}, {ext}, {num}, {date}, {old}')
    parser.add_argument('-p', '--path', default='.', help='Путь к папке (по умолчанию текущая)')
    parser.add_argument('-r', '--recursive', action='store_true', help='Рекурсивно обрабатывать подпапки')
    parser.add_argument('-e', '--ext', help='Фильтр по расширению (через запятую)')
    parser.add_argument('-f', '--filter', help='Фильтр по имени (регулярное выражение)')
    parser.add_argument('--prefix', help='Добавить префикс')
    parser.add_argument('--suffix', help='Добавить суффикс (перед расширением)')
    parser.add_argument('--replace', nargs=2, metavar=('FROM', 'TO'), help='Заменить подстроку (regex)')
    parser.add_argument('--case', choices=['lower', 'upper', 'title'], help='Изменить регистр')
    parser.add_argument('--number', type=int, default=None, help='Начать нумерацию с этого числа (подставляется в {num})')
    parser.add_argument('--dry-run', action='store_true', help='Только показать изменения')
    parser.add_argument('-v', '--verbose', action='store_true', help='Подробный вывод')
    args = parser.parse_args()

    try:
        files = get_files(args.path, args.recursive, args.ext, args.filter)
    except Exception as e:
        sys.exit(colorize(f"Ошибка: {e}", 'red'))

    if not files:
        print(colorize("Нет файлов, соответствующих условиям.", 'yellow'))
        return

    # Сортировка для детерминированного порядка
    files.sort()

    num_counter = args.number if args.number is not None else 1
    renamed = 0

    for file in files:
        old_name = file.name
        ext = file.suffix[1:]  # без точки
        name_without_ext = file.stem

        # Вычисляем новый номер
        current_num = num_counter
        num_counter += 1

        # Генерируем новое имя
        new_name = apply_template(
            args.template,
            name_without_ext,
            ext,
            current_num,
            args.prefix,
            args.suffix,
            args.replace[0] if args.replace else None,
            args.replace[1] if args.replace else None,
            args.case
        )

        # Если расширение не указано в шаблоне, добавляем его
        if not re.search(r'\{ext\}', args.template) and ext:
            new_name = f"{new_name}.{ext}"

        new_path = file.parent / new_name

        if args.dry_run or args.verbose:
            print(f"{colorize('->', 'blue')} {old_name} -> {new_name}")

        if not args.dry_run:
            # Проверка конфликта
            if new_path.exists() and new_path != file:
                # Добавляем суффикс
                base = new_path.stem
                ext = new_path.suffix
                counter = 1
                while new_path.exists():
                    new_path = file.parent / f"{base}_{counter}{ext}"
                    counter += 1
                if args.verbose:
                    print(colorize(f"  Конфликт, новое имя: {new_path.name}", 'yellow'))

            try:
                file.rename(new_path)
                renamed += 1
            except Exception as e:
                print(colorize(f"Ошибка при переименовании {old_name}: {e}", 'red'))

    if args.dry_run:
        print(colorize(f"Симуляция завершена. Будет переименовано {len(files)} файлов.", 'green'))
    else:
        print(colorize(f"Переименовано {renamed} файлов.", 'green'))

if __name__ == '__main__':
    main()
