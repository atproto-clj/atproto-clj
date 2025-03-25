(ns atproto.lexicon.regex)

(def nsid
  #"^([a-zA-Z](?:[a-zA-Z0-9-]{0,61}[a-zA-Z0-9])?(?:\.[a-zA-Z0-9](?:[a-zA-Z0-9-]{0,61}[a-zA-Z0-9])?)+)\.([a-zA-Z](?:[a-zA-Z0-9]{0,62})?)$")

(def rfc3339
  #"^[0-9]{4}-[01][0-9]-[0-3][0-9]T[0-2][0-9]:[0-6][0-9]:[0-6][0-9](.[0-9]{1,20})?(Z|([+-][0-2][0-9]:[0-5][0-9]))$")

(def did
  #"^did:[a-z]+:[a-zA-Z0-9._:%-]*[a-zA-Z0-9._-]$")

(def handle
  #"^([a-zA-Z0-9]([a-zA-Z0-9-]{0,61}[a-zA-Z0-9])?\.)+[a-zA-Z]([a-zA-Z0-9-]{0,61}[a-zA-Z0-9])?$")

;; todo: verify it works in JavaScript where the backslash may not need to be escaped inside the character class
(def at-uri
  #"^at:\/\/(?<authority>[a-zA-Z0-9._:%-]+)(?:\/(?<collection>[a-zA-Z0-9-.]+)(?:\/(?<rkey>[a-zA-Z0-9._~:@!$&%')(*+,;=-]+))?)?(?:#(?<fragment>\/[a-zA-Z0-9._~:@!$&%')(*+,;=\\-[\\]\\]*))?$")

(def record-key
  #"^[a-zA-Z0-9_~.:-]{1,512}$")

(def tid
  #"^[234567abcdefghij][234567abcdefghijklmnopqrstuvwxyz]{12}$")

(def mime-type
  #"^(\w+)\/((?:[\w\.-]+)(?:\+[\w\.-]+)?)(?:\s*;\s*([\S\.-=]+)?)?$")

(def bcp47
  #"^((?<grandfathered>(en-GB-oed|i-ami|i-bnn|i-default|i-enochian|i-hak|i-klingon|i-lux|i-mingo|i-navajo|i-pwn|i-tao|i-tay|i-tsu|sgn-BE-FR|sgn-BE-NL|sgn-CH-DE)|(art-lojban|cel-gaulish|no-bok|no-nyn|zh-guoyu|zh-hakka|zh-min|zh-min-nan|zh-xiang))|((?<language>([A-Za-z]{2,3}(-(?<extlang>[A-Za-z]{3}(-[A-Za-z]{3}){0,2}))?)|[A-Za-z]{4}|[A-Za-z]{5,8})(-(?<script>[A-Za-z]{4}))?(-(?<region>[A-Za-z]{2}|[0-9]{3}))?(-(?<variant>[A-Za-z0-9]{5,8}|[0-9][A-Za-z0-9]{3}))*(-(?<extension>[0-9A-WY-Za-wy-z](-[A-Za-z0-9]{2,8})+))*(-(?<privateUseA>x(-[A-Za-z0-9]{1,8})+))?)|(?<privateUseB>x(-[A-Za-z0-9]{1,8})+))$")
