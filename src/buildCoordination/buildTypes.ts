export type BuildPlacement = {
    x: number;
    y: number;
    z: number;
    block: string;
};

export type BuildPlan = {
    planId: string;
    placements: BuildPlacement[];
};

export type BuildSection = {
    sectionId: number;
    placements: BuildPlacement[];
};

export type BuildAssignment = {
    botName: string;
    sectionId: number;
};

export type ClaimedPlacement = BuildPlacement & {
    key: string;
    claimedBy: string;
    sectionId: number;
};
