cat "$1" | elasticsearch_loader --es-host "http://localhost:9200" --index-settings-file "index_settings_search.json" --with-retry --bulk-size 5000 --index searches --type search json --json-lines -
