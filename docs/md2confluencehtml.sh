#!/bin/bash

MDFILE=$1
if [[ -z "$MDFILE" ]]; then
    echo "Missing arg: path to Markdown file" &>2
    exit 1
fi

HTMLFILE=${MDFILE%.md}.html

pandoc -o $HTMLFILE $MDFILE




awk '/<pre><code>/,/<\/code><\/pre>/ {gsub(/&quot;/, "\""); gsub(/&lt;/, "<"); gsub(/&gt;/, ">"); gsub(/&#39;/, ""); sub(/<pre><code>/, "<ac:structured-macro ac:name=\"code\"><ac:plain-text-body><![CDATA["); sub(/<\/code><\/pre>/, "]]></ac:plain-text-body></ac:structured-macro>");} {print}' $HTMLFILE > $HTMLFILE.confluence
