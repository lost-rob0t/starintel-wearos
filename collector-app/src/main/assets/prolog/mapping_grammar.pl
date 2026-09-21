:- module(starintel_mapping_grammar, [
    parse_mapping_rule/2,
    parse_mapping_rules/2
]).

:- use_module(library(dcg/basics)).
:- use_module(library(date)).

/*
Example:

map field_ops to dataset downtown
  from "2026-09-20T20:00:00Z"
  to "2026-09-21T04:00:00Z"
  within 750 meters of 39.9612,-82.9988
  kinds [wifi,audio,video,location]
  priority 80.

The grammar emits:
dataset_rule(field_ops,downtown,StartMs,EndMs,39.9612,-82.9988,750,
             [wifi,audio,video,location],80).
*/

parse_mapping_rule(Text, Rule) :-
    string_codes(Text, Codes),
    phrase(mapping_rule(Rule), Codes).

parse_mapping_rules(Text, Rules) :-
    split_string(Text, ".", " \t\r\n", Chunks),
    findall(
        Rule,
        (
            member(Chunk, Chunks),
            Chunk \= "",
            string_codes(Chunk, Codes),
            phrase(mapping_rule_without_dot(Rule), Codes)
        ),
        Rules
    ).

mapping_rule(Rule) -->
    mapping_rule_without_dot(Rule),
    blanks,
    ".",
    blanks,
    eos.

mapping_rule_without_dot(
    dataset_rule(Id, Dataset, StartMs, EndMs, Lat, Lon, Radius, Kinds, Priority)
) -->
    blanks,
    "map", blanks1, identifier(Id),
    blanks1, "to", blanks1, "dataset", blanks1, identifier(Dataset),
    blanks1, time_clause(StartMs, EndMs),
    blanks1, geo_clause(Lat, Lon, Radius),
    blanks1, kinds_clause(Kinds),
    blanks1, priority_clause(Priority),
    blanks.

time_clause(StartMs, EndMs) -->
    "from", blanks1, timestamp(StartMs),
    blanks1, "to", blanks1, timestamp(EndMs).
time_clause(any, any) -->
    "anytime".

geo_clause(Lat, Lon, Radius) -->
    "within", blanks1, number(Radius0), blanks1, meter_word,
    blanks1, "of", blanks1,
    number(Lat0), blanks, ",", blanks, number(Lon0),
    { Radius is Radius0, Lat is Lat0, Lon is Lon0 }.
geo_clause(any, any, any) -->
    "anywhere".

kinds_clause(Kinds) -->
    "kinds", blanks1, "[", blanks, identifier_list(Kinds), blanks, "]".
kinds_clause(any) -->
    "kinds", blanks1, "any".

priority_clause(Priority) -->
    "priority", blanks1, integer(Priority).

meter_word --> "m".
meter_word --> "meter".
meter_word --> "meters".

identifier_list([Head|Tail]) -->
    identifier(Head),
    blanks,
    identifier_tail(Tail).

identifier_tail([Head|Tail]) -->
    ",", blanks, identifier(Head), blanks, identifier_tail(Tail).
identifier_tail([]) --> [].

identifier(Value) -->
    identifier_codes(Codes),
    { Codes \= [], atom_codes(Value, Codes) }.

identifier_codes([Code|Rest]) -->
    [Code],
    { identifier_code(Code) },
    !,
    identifier_codes(Rest).
identifier_codes([]) --> [].

identifier_code(Code) :-
    code_type(Code, alnum);
    memberchk(Code, [0'_, 0'-, 0'., 0':]).

timestamp(Milliseconds) -->
    quoted_string(Codes),
    {
        string_codes(Text, Codes),
        parse_time(Text, iso_8601, Seconds),
        Milliseconds is round(Seconds * 1000)
    }.

quoted_string(Codes) -->
    "\"", string_without("\"", Codes), "\"".
