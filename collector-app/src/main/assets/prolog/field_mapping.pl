:- module(starintel_field_mapping, [
    classify_observation/3,
    matching_rule/2,
    distance_m/5,
    dataset_rule/9
]).

:- dynamic dataset_rule/9.

/*
dataset_rule(
    RuleId,
    Dataset,
    StartMs,
    EndMs,
    CenterLat,
    CenterLon,
    RadiusMeters,
    Kinds,
    Priority
).

StartMs/EndMs may be any.
CenterLat/CenterLon/RadiusMeters may all be any.
Kinds may be any or a list of atoms such as [wifi,audio,video,location].

Observation term:
observation(Id, Kind, TimeMs, Lat, Lon, Attributes).
Lat/Lon may be none when a sensor record has no location.
*/

classify_observation(Observation, Dataset, RuleId) :-
    setof(
        Priority-Id-Target,
        matching_rule(Observation, matched(Priority, Id, Target)),
        Matches
    ),
    reverse(Matches, [_Priority-RuleId-Dataset|_]).

matching_rule(
    observation(_Id, Kind, TimeMs, Lat, Lon, _Attributes),
    matched(Priority, RuleId, Dataset)
) :-
    dataset_rule(
        RuleId,
        Dataset,
        StartMs,
        EndMs,
        CenterLat,
        CenterLon,
        RadiusMeters,
        Kinds,
        Priority
    ),
    kind_matches(Kinds, Kind),
    time_matches(StartMs, EndMs, TimeMs),
    geo_matches(CenterLat, CenterLon, RadiusMeters, Lat, Lon).

kind_matches(any, _Kind).
kind_matches(Kinds, Kind) :-
    is_list(Kinds),
    memberchk(Kind, Kinds).

time_matches(any, any, _TimeMs).
time_matches(StartMs, any, TimeMs) :-
    number(StartMs),
    TimeMs >= StartMs.
time_matches(any, EndMs, TimeMs) :-
    number(EndMs),
    TimeMs =< EndMs.
time_matches(StartMs, EndMs, TimeMs) :-
    number(StartMs),
    number(EndMs),
    TimeMs >= StartMs,
    TimeMs =< EndMs.

geo_matches(any, any, any, _Lat, _Lon).
geo_matches(CenterLat, CenterLon, RadiusMeters, Lat, Lon) :-
    number(CenterLat),
    number(CenterLon),
    number(RadiusMeters),
    number(Lat),
    number(Lon),
    distance_m(CenterLat, CenterLon, Lat, Lon, Distance),
    Distance =< RadiusMeters.

distance_m(Lat1, Lon1, Lat2, Lon2, Meters) :-
    DegToRad is pi / 180.0,
    Phi1 is Lat1 * DegToRad,
    Phi2 is Lat2 * DegToRad,
    DeltaPhi is (Lat2 - Lat1) * DegToRad,
    DeltaLambda is (Lon2 - Lon1) * DegToRad,
    A is sin(DeltaPhi / 2) ** 2
        + cos(Phi1) * cos(Phi2) * sin(DeltaLambda / 2) ** 2,
    C is 2 * atan2(sqrt(A), sqrt(1 - A)),
    Meters is 6371008.8 * C.
